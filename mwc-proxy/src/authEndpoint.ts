import { KeyObject, randomUUID } from 'crypto'
import { type Express } from 'express'
import { Authflow, Titles } from 'prismarine-auth'
import yggdrasil from 'yggdrasil'
import debug from 'debug'
import { updateMetrics } from './metrics'
import type { ProxyMiddlewareOptions } from './api'

const yggdrasilServer = yggdrasil.server({ host: 'https://sessionserver.mojang.com' })

class FakeFileCache {
    cache

    constructor(public cacheName: string, initial, public sharedCache) {
        this.cache = initial
    }

    async loadInitialValue() {
        return {}
    }

    async getCached() {
        if (this.cache === undefined) {
            this.cache = await this.loadInitialValue()
        }

        return this.cache
    }

    async setCached(cached) {
        this.cache = cached
        this.sharedCache[this.cacheName] = cached
    }

    async setCachedPartial(cached) {
        await this.setCached({
            ...this.cache,
            ...cached,
        })
    }
}

export default (app: Express, authTokensEndpoint = '/', sessionServerProxy = '/session', authLog = (str: string) => {}, urlRoot = '', options: ProxyMiddlewareOptions = {}) => {
    const authActiveConnections = new Map<string, number>()
    const maxPerIp = options.authMaxConcurrentPerIp ?? 2
    const maxTotal = options.authMaxConcurrentTotal ?? 5

    // Pollable sessions for clients (browsers) that cannot consume the SSE
    // stream. Each session owns a live Authflow and is polled until complete.
    const authSessions = new Map<string, any>()
    const sweepAuthSessions = () => {
        const cutoff = Date.now() - 20 * 60 * 1000
        for (const [id, session] of authSessions) {
            if (session.createdAt < cutoff) {
                if (session.authflow?.msa) session.authflow.msa.polling = false
                authSessions.delete(id)
            }
        }
    }

    if (options.authToggleDebugEndpoint) {
        const endpoint = typeof options.authToggleDebugEndpoint === 'string' ? `${urlRoot}/${options.authToggleDebugEndpoint}` : `${urlRoot}/toggle-debug`
        app.get(endpoint, (req, res) => {
            const SCOPES = ['prismarine-auth']
            if (debug.enabled(SCOPES.join(','))) {
                debug.disable()
                return res.send('Debug disabled')
            }
            debug.enable(SCOPES.join(','))
            return res.send('Debug enabled')
        })
    }

    app.use((req, res, next) => {
        if (req.path === authTokensEndpoint && req.method === 'POST') {
            const { ip } = req
            const currentCount = authActiveConnections.get(ip) || 0

            if (currentCount >= maxPerIp) {
                res.status(429).send('Too many requests from this IP, please slow down.')
                return
            }

            const totalConnections = [...authActiveConnections.values()].reduce((acc, val) => acc + val, 0)

            if (totalConnections >= maxTotal) {
                res.status(429).send('Server is busy with other requests, please try again later.')
                return
            }

            authActiveConnections.set(ip, currentCount + 1)
            res.on('close', () => {
                // console.log('close auth req')
                const currentCount = authActiveConnections.get(ip) || 0
                authActiveConnections.set(ip, currentCount - 1)
            })
            next()
        }

        next()
    })

    app.post(authTokensEndpoint, async (req, res) => {
        const sendString = (string: string) => {
            res.write(`${string}\n\n`)
            //@ts-expect-error
            res.flush()
        }

        try {
            const cachesAndOptions = req.body as { [key: string]: any }
            authLog(`Starting auth request: ${cachesAndOptions.connectingServer} ${cachesAndOptions.connectingServerVersion} ${req.ip} ${req.headers.origin}`)
            // from minecraft-protocol, for easier testing

            const bannedServers = ['mc.hypixel.net', 'hypixel.net']
            if (cachesAndOptions.connectingServer && bannedServers.includes(cachesAndOptions.connectingServer)) {
                res.status(403).send({
                    error: 'Server is not supported yet. If you interested, please let us know in the discord.',
                })
                return
            }

            if (!cachesAndOptions.flow) {
                cachesAndOptions.authTitle = Titles.MinecraftNintendoSwitch
                cachesAndOptions.deviceType = 'Nintendo'
                cachesAndOptions.flow = 'live'
            }

            const newCache = {}

            // Set headers for SSE
            res.setHeader('Content-Type', 'text/event-stream')
            res.setHeader('Cache-Control', 'no-cache')
            res.setHeader('Connection', 'keep-alive')

            const authflow = new Authflow(
                '',
                //@ts-expect-error
                ({ cacheName, username }) => new FakeFileCache(cacheName, cachesAndOptions[cacheName], newCache),
                {
                    flow: cachesAndOptions.flow,
                    msalConfig: cachesAndOptions.msalConfig,
                    authTitle: cachesAndOptions.authTitle,
                    deviceType: cachesAndOptions.deviceType,
                    deviceVersion: cachesAndOptions.deviceVersion,
                },
                data => {
                    sendString(`${JSON.stringify(data)}`)
                    setTimeout(() => {
                        res.end()
                    }, data.expires_in * 1000)
                },
            )

            // Send an event to the client
            sendString(`data: ${JSON.stringify({ message: 'Hello from the authentication server! Starting your request now.' })}`)

            // Keep the connection open by sending a comment every 5 seconds
            const interval = setInterval(() => {
                sendString(`: ${new Date().toISOString()}`)
            }, 5000)

            res.on('close', () => {
                res.end()
                clearInterval(interval)
                //@ts-expect-error
                const { msa } = authflow
                if (msa) {
                    msa.polling = false
                }
            })

            const tokenData = await authflow.getMinecraftJavaToken({
                fetchCertificates: true,
                fetchProfile: true,
                ...cachesAndOptions.getJavaTokenOptions,
            })
            transformAllKeysDeep(tokenData)
            sendString(`${JSON.stringify({ newCache })}`)
            sendString(`${JSON.stringify(tokenData)}`)
            // profileKeys.public.export
            sendString('data: {"message": "Request complete. Bye!"}')
            authLog(`Auth request complete: ${Object.keys(tokenData).join(', ')}; ${Object.keys(newCache).join(', ')}`)
            updateMetrics.recordAuthRequest(true)
            res.end()
        } catch (e) {
            try {
                console.error(e)
                sendString(`${JSON.stringify({ error: e.message })}`)
                res.status(500).send({
                    error: e.message,
                })
                updateMetrics.recordAuthRequest(false)
                res.end()
            } catch {}
        }
    })

    // Start a device-code sign-in and return the code immediately. The flow
    // keeps running server-side; the client then polls /auth/poll.
    app.post(`${urlRoot}/auth/start`, async (req, res) => {
        sweepAuthSessions()
        const body = (req.body || {}) as { [key: string]: any }
        try {
            const pending = [...authSessions.values()].filter((s) => s.state === 'pending')
            if (pending.length >= maxTotal) {
                return res.status(429).json({ error: 'Server is busy with other authentication requests, please try again later.' })
            }
            if (pending.filter((s) => s.ip === req.ip).length >= maxPerIp) {
                return res.status(429).json({ error: 'Too many authentication requests from this IP, please slow down.' })
            }

            if (!body.flow) {
                body.authTitle = Titles.MinecraftNintendoSwitch
                body.deviceType = 'Nintendo'
                body.flow = 'live'
            }

            const id = randomUUID()
            const session: any = {
                id,
                ip: req.ip,
                state: 'pending',
                createdAt: Date.now(),
                newCache: {},
                userCode: null,
            }
            authSessions.set(id, session)

            const authflow = new Authflow(
                '',
                //@ts-expect-error
                ({ cacheName }) => new FakeFileCache(cacheName, body[cacheName], session.newCache),
                {
                    flow: body.flow,
                    msalConfig: body.msalConfig,
                    authTitle: body.authTitle,
                    deviceType: body.deviceType,
                    deviceVersion: body.deviceVersion,
                },
                (data) => {
                    session.userCode = data.user_code
                    session.verificationUri = data.verification_uri
                    session.message = data.message
                    session.interval = data.interval
                    session.expiresIn = data.expires_in
                },
            )
            session.authflow = authflow

            // Kick off the flow but do NOT await it: the client polls
            // /auth/poll for the device code and the final token. This keeps
            // every HTTP call short so a tick-driven client never blocks.
            session.done = authflow
                .getMinecraftJavaToken({
                    fetchCertificates: true,
                    fetchProfile: true,
                    ...body.getJavaTokenOptions,
                })
                .then(
                    (tokenData) => {
                        transformAllKeysDeep(tokenData)
                        session.tokenData = tokenData
                        session.state = 'complete'
                        authLog(`Auth session ${id} complete: ${Object.keys(tokenData).join(', ')}`)
                        updateMetrics.recordAuthRequest(true)
                    },
                    (err) => {
                        session.state = 'error'
                        session.error = err?.message || String(err)
                        authLog(`Auth session ${id} failed: ${session.error}`)
                        updateMetrics.recordAuthRequest(false)
                    },
                )

            if (session.state === 'complete') {
                return res.json({ sessionId: id, state: 'complete', newCache: session.newCache, tokenData: session.tokenData })
            }
            if (session.state === 'error') {
                return res.json({ sessionId: id, state: 'error', error: session.error || 'Authentication failed' })
            }
            return res.json({ sessionId: id, state: 'pending' })
        } catch (e: any) {
            console.error(e)
            try {
                res.status(500).json({ error: e?.message || String(e) })
            } catch {}
        }
    })

    app.post(`${urlRoot}/auth/poll`, (req, res) => {
        sweepAuthSessions()
        const id = (req.body || {}).sessionId
        const session = id ? authSessions.get(id) : undefined
        if (!session) {
            return res.status(404).json({ state: 'expired' })
        }
        if (session.state === 'complete') {
            return res.json({ state: 'complete', newCache: session.newCache, tokenData: session.tokenData })
        }
        if (session.state === 'error') {
            return res.json({ state: 'error', error: session.error || 'Authentication failed' })
        }
        return res.json({
            state: 'pending',
            user_code: session.userCode,
            verification_uri: session.verificationUri,
            message: session.message,
            interval: session.interval,
            expires_in: session.expiresIn,
        })
    })

    app.post(sessionServerProxy, async (req, res) => {
        try {
            authLog(`Join request received`)
            yggdrasilServer.join(
                req.body.accessToken,
                req.body.selectedProfile,
                req.body.serverId,
                new Uint8Array(req.body.sharedSecret.data),
                new Uint8Array(req.body.publicKey.data),
                err => {
                    if (err) {
                        console.error(err)
                        return res.status(403).send({
                            error: err.message,
                        })
                    }

                    return res.status(204).send()
                },
            )
        } catch (err) {
            console.error(err)
            try {
                res.status(500).send({
                    error: err.message,
                })
            } catch {}
        }
    })
}

const transformAllKeysDeep = obj => {
    if (typeof obj !== 'object' || obj === null) return obj
    const keys = Object.keys(obj)
    const result = {}
    for (const key of keys) {
        const value = obj[key]
        if (value instanceof KeyObject) {
            const isPrivate = value.type === 'private'
            result[key] = value.export({
                format: 'der',
                type: isPrivate ? 'pkcs8' : 'spki',
            })
        }

        transformAllKeysDeep(value)
    }
}
