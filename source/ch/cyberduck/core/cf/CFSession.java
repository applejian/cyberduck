package ch.cyberduck.core.cf;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.*;
import ch.cyberduck.core.cloud.CloudHTTP3Session;
import ch.cyberduck.core.cloud.Distribution;
import ch.cyberduck.core.i18n.Locale;
import ch.cyberduck.core.ssl.AbstractX509TrustManager;

import org.apache.log4j.Logger;

import com.rackspacecloud.client.cloudfiles.FilesCDNContainer;
import com.rackspacecloud.client.cloudfiles.FilesClient;
import com.rackspacecloud.client.cloudfiles.FilesException;

import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.*;

/**
 * Rackspace Cloud Files Implementation
 *
 * @version $Id$
 */
public class CFSession extends CloudHTTP3Session {
    private static Logger log = Logger.getLogger(CFSession.class);

    private static class Factory extends SessionFactory {
        @Override
        protected Session create(Host h) {
            return new CFSession(h);
        }
    }

    public static SessionFactory factory() {
        return new Factory();
    }

    private FilesClient CF;

    public CFSession(Host h) {
        super(h);
    }

    @Override
    protected FilesClient getClient() throws ConnectionCanceledException {
        if(null == CF) {
            throw new ConnectionCanceledException();
        }
        return CF;
    }

    @Override
    protected void connect() throws IOException {
        if(this.isConnected()) {
            return;
        }
        this.CF = new FilesClient(null, null, null, this.timeout());
        this.fireConnectionWillOpenEvent();

        // Configure for authentication URL
        this.configure();

        // Prompt the login credentials first
        this.login();

        // Configure for storage URL
        this.configure();

        this.fireConnectionDidOpenEvent();
    }

    /**
     * Set connection properties
     */
    protected void configure() throws ConnectionCanceledException {
        FilesClient client = this.getClient();
        client.setConnectionTimeOut(this.timeout());
        client.setUserAgent(this.getUserAgent());
        if(!client.isLoggedin()) {
            Host host = this.getHost();
            StringBuilder authentication = new StringBuilder(host.getProtocol().getScheme()).append("://");
            if(host.getHostname().equals(host.getProtocol().getDefaultHostname())) {
                // Use default authentication server. Rackspace.
                authentication.append(Preferences.instance().getProperty("cf.authentication.host"));
            }
            else {
                // Use custom authentication server. Swift (OpenStack Object Storage) installation.
                authentication.append(host.getHostname());
                authentication.append(":").append(host.getPort());
            }
            authentication.append(Preferences.instance().getProperty("cf.authentication.context"));
            log.info("Using authentication URL " + authentication.toString());
            client.setAuthenticationURL(authentication.toString());
            URI url = URI.create(authentication.toString());
            client.setHostConfiguration(this.getHostConfiguration(url.getScheme(), url.getHost(), url.getPort()));
        }
        else {
            URI url = URI.create(client.getStorageURL());
            client.setHostConfiguration(this.getHostConfiguration(url.getScheme(), url.getHost(), url.getPort()));
        }
    }

    @Override
    protected void login(LoginController controller, Credentials credentials) throws IOException {
        FilesClient client = this.getClient();
        client.setUserName(credentials.getUsername());
        client.setPassword(credentials.getPassword());
        if(!client.login()) {
            this.message(Locale.localizedString("Login failed", "Credentials"));
            controller.fail(host.getProtocol(), credentials);
            this.login();
        }
    }

    @Override
    public void close() {
        try {
            if(this.isConnected()) {
                this.fireConnectionWillCloseEvent();
            }
        }
        finally {
            // No logout required
            CF = null;
            this.fireConnectionDidCloseEvent();
        }
    }

    /**
     * @return No Content-Range support
     */
    @Override
    public boolean isDownloadResumable() {
        return false;
    }

    /**
     * @return No Content-Range support
     */
    @Override
    public boolean isUploadResumable() {
        return false;
    }

    /**
     * Renaming is not currently supported
     *
     * @return Always false
     */
    @Override
    public boolean isRenameSupported(Path file) {
        return false;
    }

    /**
     * Creating files is only possible inside a bucket.
     *
     * @param workdir The workdir to create query
     * @return False if directory is root.
     */
    @Override
    public boolean isCreateFileSupported(Path workdir) {
        return !workdir.isRoot();
    }
    
    /**
     * Cache distribution status result.
     */
    private Map<String, Distribution> distributionStatus
            = new HashMap<String, Distribution>();

    @Override
    public Distribution getDistribution(String container, Distribution.Method method) {
        return distributionStatus.get(container);
    }

    /**
     * @param enabled Enable content distribution for the container
     * @param method
     * @param cnames  Currently ignored
     * @param logging
     */
    @Override
    public void writeDistribution(boolean enabled, String container, Distribution.Method method,
                                  String[] cnames, boolean logging, String defaultRootObject) {
        final AbstractX509TrustManager trust = this.getTrustManager();
        try {
            this.check();
            URI url = URI.create(this.getClient().getCdnManagementURL());
            this.getClient().setHostConfiguration(
                    this.getHostConfiguration(url.getScheme(), url.getHost(), url.getPort()));
            if(enabled) {
                this.message(MessageFormat.format(Locale.localizedString("Enable {0} Distribution", "Status"),
                        Locale.localizedString("Rackspace Cloud Files", "Mosso")));
            }
            else {
                this.message(MessageFormat.format(Locale.localizedString("Disable {0} Distribution", "Status"),
                        Locale.localizedString("Rackspace Cloud Files", "Mosso")));
            }
            if(enabled) {
                try {
                    final FilesCDNContainer info = this.getClient().getCDNContainerInfo(container);
                }
                catch(FilesException e) {
                    log.warn(e.getMessage());
                    // Not found.
                    this.getClient().cdnEnableContainer(container);
                }
            }
            // Toggle content distribution for the container without changing the TTL expiration
            this.getClient().cdnUpdateContainer(container, -1, enabled, logging);
        }
        catch(IOException e) {
            this.error("Cannot write CDN configuration", e);
        }
        finally {
            try {
                // Configure for storage URL
                this.configure();
            }
            catch(ConnectionCanceledException e) {
                log.error(e.getMessage());
            }
            distributionStatus.clear();
        }
    }

    @Override
    public Distribution readDistribution(String container, Distribution.Method method) {
        if(!distributionStatus.containsKey(container)) {
            final AbstractX509TrustManager trust = this.getTrustManager();
            try {
                this.check();
                URI url = URI.create(this.getClient().getCdnManagementURL());
                this.getClient().setHostConfiguration(
                        this.getHostConfiguration(url.getScheme(), url.getHost(), url.getPort()));
                try {
                    final FilesCDNContainer info = this.getClient().getCDNContainerInfo(container);
                    final Distribution distribution = new Distribution(info.getName(), info.isEnabled(), info.getCdnURL(),
                            info.isEnabled() ? Locale.localizedString("CDN Enabled", "Mosso") : Locale.localizedString("CDN Disabled", "Mosso"),
                            info.getRetainLogs());
                    if(distribution.isDeployed()) {
                        distributionStatus.put(container, distribution);
                    }
                    return distribution;
                }
                catch(FilesException e) {
                    log.warn(e.getMessage());
                    // Not found.
                    distributionStatus.put(container, new Distribution(null, false, null, Locale.localizedString("CDN Disabled", "Mosso")));
                }
            }
            catch(IOException e) {
                this.error("Cannot read CDN configuration", e);
            }
            finally {
                try {
                    // Configure for storage URL
                    this.configure();
                }
                catch(ConnectionCanceledException e) {
                    log.error(e.getMessage());
                }
            }
        }
        if(distributionStatus.containsKey(container)) {
            return distributionStatus.get(container);
        }
        return new Distribution();
    }

    @Override
    public String getDistributionServiceName() {
        return Locale.localizedString("Limelight Content", "Mosso");
    }

    @Override
    public List<Distribution.Method> getSupportedDistributionMethods() {
        return Arrays.asList(Distribution.DOWNLOAD);
    }

    @Override
    public List<String> getSupportedStorageClasses() {
        return Collections.emptyList();
    }

    @Override
    public boolean isCDNSupported() {
        return true;
    }
}