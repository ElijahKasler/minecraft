package net.lax1dude.eaglercraft.v1_8.socket.mwc;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Persisted list of mwc proxy servers shown in the Proxy Settings screen,
 * stored under the {@link EagRuntime} blob storage key {@code "m"}.
 *
 * <p>Entries are plain {@code name / http(s)://host:port} pairs. The first
 * entry ("Local Proxy") always defaults to {@code http://localhost:2344} but
 * its address can be edited like any other entry. The entry selected in the
 * GUI becomes the active {@code GameSettings.mwcProxyBase}.</p>
 */
public class MwcProxyListManager {

	public static final Logger logger = LogManager.getLogger("MwcProxyListManager");

	public static final MwcProxyListManager proxies = new MwcProxyListManager();

	public static final String DEFAULT_NAME = "Local Proxy";
	public static final String DEFAULT_ADDRESS = "http://localhost:2344";

	public static class MwcProxyEntry {
		public String name;
		public String address;

		public MwcProxyEntry(String name, String address) {
			this.name = name;
			this.address = address;
		}
	}

	private final List<MwcProxyEntry> entries = new ArrayList<>();
	private boolean loaded = false;

	private void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		byte[] data = null;
		try {
			data = EagRuntime.getStorage("m");
		} catch (Throwable t) {
			logger.warn("Could not read mwc proxy list storage: {}", t.toString());
		}
		if (data != null) {
			try {
				String[] lines = new String(data, "UTF-8").split("\n");
				for (int i = 0; i < lines.length; ++i) {
					String line = lines[i].trim();
					if (line.isEmpty()) {
						continue;
					}
					int tab = line.indexOf('\t');
					if (tab <= 0 || tab == line.length() - 1) {
						continue;
					}
					String name = line.substring(0, tab).trim();
					String addr = line.substring(tab + 1).trim();
					if (!addr.isEmpty()) {
						entries.add(new MwcProxyEntry(name.isEmpty() ? addr : name, addr));
					}
				}
			} catch (Throwable t) {
				logger.warn("Could not parse mwc proxy list: {}", t.toString());
				entries.clear();
			}
		}
		if (entries.isEmpty()) {
			entries.add(new MwcProxyEntry(DEFAULT_NAME, DEFAULT_ADDRESS));
			save();
		}
	}

	public void loadDefaults() {
		ensureLoaded();
		entries.clear();
		entries.add(new MwcProxyEntry(DEFAULT_NAME, DEFAULT_ADDRESS));
		save();
	}

	public void save() {
		if (!loaded) {
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0, l = entries.size(); i < l; ++i) {
			MwcProxyEntry e = entries.get(i);
			sb.append(e.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
					.append(e.address.replace('\t', ' ').replace('\n', ' ')).append('\n');
		}
		try {
			EagRuntime.setStorage("m", sb.toString().getBytes("UTF-8"));
		} catch (Throwable t) {
			logger.warn("Could not save mwc proxy list: {}", t.toString());
		}
	}

	public int count() {
		ensureLoaded();
		return entries.size();
	}

	public MwcProxyEntry get(int index) {
		ensureLoaded();
		return entries.get(index);
	}

	public void add(String name, String address) {
		ensureLoaded();
		entries.add(new MwcProxyEntry(name, address));
		save();
	}

	public void update(int index, String name, String address) {
		ensureLoaded();
		if (index >= 0 && index < entries.size()) {
			entries.set(index, new MwcProxyEntry(name, address));
			save();
		}
	}

	public void remove(int index) {
		ensureLoaded();
		if (index >= 0 && index < entries.size()) {
			entries.remove(index);
			save();
		}
	}
}
