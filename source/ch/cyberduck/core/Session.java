package ch.cyberduck.core;

/*
 *  Copyright (c) 2004 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Observable;

import com.apple.cocoa.foundation.NSBundle;

import org.apache.log4j.Logger;

import ch.cyberduck.ui.cocoa.growl.Growl;

/**
 * @version $Id$
 */
public abstract class Session extends Observable {
	private static Logger log = Logger.getLogger(Session.class);

	public static final String SFTP = "sftp";
	public static final String FTP = "ftp";
//    public static final String FTPS = "ftps";

	private Transcript transcript;

	private Cache cache = new Cache();

	/**
	 * Default port for http
	 */
	public static final int HTTP_PORT = 80;
	/**
	 * Default port for https
	 */
	public static final int HTTPS_PORT = 443;
	/**
	 * Default port for ftp
	 */
	public static final int FTP_PORT = 21;
	/**
	 * Default port for ftp-ssl
	 */
//    public static final int FTPS_PORT = 990;
	/**
	 * Default port for ssh
	 */
	public static final int SSH_PORT = 22;

	/**
	 * Encapsulating all the information of the remote host
	 */
	public Host host;

	private List history = null;

	private boolean connected;

	public Session copy() {
		return SessionFactory.createSession(this.host);
	}

	protected Session(Host h) {
		log.debug("Session("+h+")");
		this.host = h;
		this.history = new ArrayList();
		this.transcript = TranscriptFactory.getImpl(host.getHostname());
	}

	public void callObservers(Object arg) {
		this.setChanged();
		this.notifyObservers(arg);
	}

	/**
	 * Connect to the remote Host
	 * The protocol specific implementation has to  be coded in the subclasses.
	 *
	 * @see Host
	 */
	public abstract void connect(String encoding) throws IOException;

	public synchronized void connect() throws IOException {
		this.connect(Preferences.instance().getProperty("browser.charset.encoding"));
	}

	/**
	 * Connect to the remote host and mount the home directory
	 */
	public synchronized void mount() {
		this.log("Mounting "+host.getHostname()+"...", Message.PROGRESS);
		new Thread() {
			public void run() {
				try {
					check();
					Path home;
					if(host.hasReasonableDefaultPath()) {
						if(host.getDefaultPath().charAt(0) != '/') {
							home = PathFactory.createPath(Session.this, workdir().getAbsolute(), host.getDefaultPath());
						}
						else {
							home = PathFactory.createPath(Session.this, host.getDefaultPath());
						}
					}
					else {
						home = workdir();
						//host.setDefaultPath(home);
					}
					home.list(true);
					Growl.instance().notify(NSBundle.localizedString("Connection opened", "Growl Notification"),
					    host.getHostname());
				}
				catch(IOException e) {
					log("IO Error: "+e.getMessage(), Message.ERROR);
					Growl.instance().notify(NSBundle.localizedString("Connection failed", "Growl Notification"),
					    host.getHostname());
					close();
				}
			}
		}.start();
	}

	/**
	 * Close the connecion to the remote host.
	 * The protocol specific implementation has to  be coded in the subclasses.
	 *
	 * @see Host
	 */
	public abstract void close();

	public void recycle() throws IOException {
		log.info("Recycling session");
		this.close();
		this.connect();
	}

	public Host getHost() {
		return this.host;
	}

	/**
	 * @return The current working directory (pwd)
	 */
	public abstract Path workdir();

	/**
	 * Assert that the connection to the remote host is still alive. Open connection if needed.
	 *
	 * @throws IOException The connection to the remote host failed.
	 * @see Host
	 */
	public abstract void check() throws IOException;

	/**
	 * @return boolean True if the session has not yet been closed.
	 */
	public boolean isConnected() {
		return this.connected;
	}

	public void setConnected() throws IOException {
		log.debug("setConnected");
		SessionPool.instance().add(this);
		this.callObservers(new Message(Message.OPEN, "Session opened."));
		this.connected = true;
	}

	public void setClosed() {
		log.debug("setClosed");
		SessionPool.instance().release(this);
		this.callObservers(new Message(Message.CLOSE, "Session closed."));
		this.connected = false;
	}

	public void addPathToHistory(Path p) {
		if(history.size() > 0) {
			if(!p.equals(history.get(history.size()-1))) {
				this.history.add(p);
			}
		}
		else {
			this.history.add(p);
		}
	}

	public Path getPreviousPath() {
		int size = history.size();
		if(size > 1) {
			Path p = (Path)history.get(size-2);
			//delete the fetched path - otherwise we produce a loop
			history.remove(size-1);
			history.remove(size-2);
			return p;
		}
		else if(1 == size) {
			return (Path)history.get(size-1);
		}
		return workdir();
	}

	public Cache cache() {
		return this.cache;
	}

	public void log(String message, String title) {
		if(title.equals(Message.TRANSCRIPT)) {
			this.transcript.log(message);
		}
		else {
			this.callObservers(new Message(title, message));
		}
	}
}