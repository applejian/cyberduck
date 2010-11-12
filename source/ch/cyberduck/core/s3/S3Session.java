package ch.cyberduck.core.s3;

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
import ch.cyberduck.core.http.StickyHostConfiguration;
import ch.cyberduck.core.i18n.Locale;

import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpHost;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.auth.AuthScheme;
import org.apache.commons.httpclient.auth.CredentialsNotAvailableException;
import org.apache.commons.httpclient.auth.CredentialsProvider;
import org.apache.commons.httpclient.methods.RequestEntity;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.jets3t.service.CloudFrontService;
import org.jets3t.service.CloudFrontServiceException;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.ServiceException;
import org.jets3t.service.acl.GroupGrantee;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.*;
import org.jets3t.service.model.cloudfront.DistributionConfig;
import org.jets3t.service.model.cloudfront.InvalidationSummary;
import org.jets3t.service.model.cloudfront.LoggingStatus;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.security.ProviderCredentials;
import org.jets3t.service.utils.ServiceUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

/**
 * Connecting to S3 service with plain HTTP.
 *
 * @version $Id$
 */
public class S3Session extends CloudHTTP3Session {
    private static Logger log = Logger.getLogger(S3Session.class);

    private static class Factory extends SessionFactory {
        @Override
        protected Session create(Host h) {
            return new S3Session(h);
        }
    }

    public static SessionFactory factory() {
        return new Factory();
    }

    private RequestEntityRestStorageService S3;

    protected S3Session(Host h) {
        super(h);
    }

    @Override
    protected RequestEntityRestStorageService getClient() throws ConnectionCanceledException {
        if(null == S3) {
            throw new ConnectionCanceledException();
        }
        return S3;
    }

    /**
     * Exposing protected methods
     */
    protected class RequestEntityRestStorageService extends RestS3Service {
        public RequestEntityRestStorageService(ProviderCredentials credentials) throws ServiceException {
            super(credentials, S3Session.this.getUserAgent(), new CredentialsProvider() {
                /**
                 * Implementation method for the CredentialsProvider interface
                 * @throws CredentialsNotAvailableException
                 */
                public org.apache.commons.httpclient.Credentials getCredentials(AuthScheme authscheme, String hostname, int port, boolean proxy)
                        throws CredentialsNotAvailableException {
                    log.error("Additional HTTP authentication not supported:" + authscheme.getSchemeName());
                    throw new CredentialsNotAvailableException("Unsupported authentication scheme: " +
                            authscheme.getSchemeName());
                }
            }, S3Session.this.getProperties(), S3Session.this.getHostConfiguration());
        }

        /**
         * Exposing implementation method.
         *
         * @param bucketName
         * @param object
         * @param requestEntity
         * @throws ServiceException
         */
        @Override
        public void pubObjectWithRequestEntityImpl(String bucketName, StorageObject object,
                                                   RequestEntity requestEntity) throws ServiceException {
            super.pubObjectWithRequestEntityImpl(bucketName, object, requestEntity);
        }
    }

    /**
     *
     */
    private Jets3tProperties configuration
            = new Jets3tProperties();

    /**
     * @return Client configuration
     */
    protected Jets3tProperties getProperties() {
        return configuration;
    }

    protected void configure() {
        if(StringUtils.isNotBlank(host.getProtocol().getDefaultHostname())
                && host.getHostname().endsWith(host.getProtocol().getDefaultHostname())) {
            // The user specified a DNS bucket endpoint. Connect to the default hostname instead.
            configuration.setProperty("s3service.s3-endpoint", host.getProtocol().getDefaultHostname());
        }
        else {
            // Standard configuration
            configuration.setProperty("s3service.s3-endpoint", host.getHostname());
            configuration.setProperty("s3service.disable-dns-buckets", String.valueOf(true));
        }
        configuration.setProperty("s3service.enable-storage-classes", String.valueOf(true));
        configuration.setProperty("s3service.https-only", String.valueOf(host.getProtocol().isSecure()));
        // The maximum number of retries that will be attempted when an S3 connection fails
        // with an InternalServer error. To disable retries of InternalError failures, set this to 0.
        configuration.setProperty("s3service.internal-error-retry-max", String.valueOf(0));
        // The maximum number of concurrent communication threads that will be started by
        // the multi-threaded service for upload and download operations.
        configuration.setProperty("s3service.max-thread-count", String.valueOf(1));

        configuration.setProperty("httpclient.proxy-autodetect", String.valueOf(false));
        if(Preferences.instance().getBoolean("connection.proxy.enable")) {
            final Proxy proxy = ProxyFactory.instance();
            if(host.getProtocol().isSecure()) {
                if(proxy.isHTTPSProxyEnabled()) {
                    configuration.setProperty("httpclient.proxy-host", proxy.getHTTPSProxyHost());
                    configuration.setProperty("httpclient.proxy-port", String.valueOf(proxy.getHTTPSProxyPort()));
                    configuration.setProperty("httpclient.proxy-user", null);
                    configuration.setProperty("httpclient.proxy-password", null);
                    if(StringUtils.isNotEmpty(Preferences.instance().getProperty("connection.proxy.ntlm.domain"))) {
                        configuration.setProperty("httpclient.proxy-domain",
                                Preferences.instance().getProperty("connection.proxy.ntlm.domain"));
                    }
                }
            }
            else {
                if(proxy.isHTTPProxyEnabled()) {
                    configuration.setProperty("httpclient.proxy-host", proxy.getHTTPProxyHost());
                    configuration.setProperty("httpclient.proxy-port", String.valueOf(proxy.getHTTPProxyPort()));
                    configuration.setProperty("httpclient.proxy-user", null);
                    configuration.setProperty("httpclient.proxy-password", null);
                    if(StringUtils.isNotEmpty(Preferences.instance().getProperty("connection.proxy.ntlm.domain"))) {
                        configuration.setProperty("httpclient.proxy-domain",
                                Preferences.instance().getProperty("connection.proxy.ntlm.domain"));
                    }
                }
            }
        }
        configuration.setProperty("httpclient.connection-timeout-ms", String.valueOf(this.timeout()));
        configuration.setProperty("httpclient.socket-timeout-ms", String.valueOf(this.timeout()));
        configuration.setProperty("httpclient.useragent", this.getUserAgent());
        configuration.setProperty("httpclient.authentication-preemptive", String.valueOf(false));

        // How many times to retry connections when they fail with IO errors. Set this to 0 to disable retries.
        configuration.setProperty("httpclient.retry-max", String.valueOf(0));
    }

    /**
     * @param bucket
     * @return
     */
    @Override
    public String getHostnameForContainer(String bucket) {
        if(configuration.getBoolProperty("s3service.disable-dns-buckets", false)) {
            return this.getHost().getHostname(true);
        }
        if(!ServiceUtils.isBucketNameValidDNSName(bucket)) {
            return this.getHost().getHostname(true);
        }
        if(this.getHost().getHostname().equals(this.getHost().getProtocol().getDefaultHostname())) {
            return bucket + "." + this.getHost().getHostname(true);
        }
        return this.getHost().getHostname(true);
    }

    /**
     * Caching the uses's buckets
     */
    private Map<String, S3Bucket> buckets
            = new HashMap<String, S3Bucket>();

    /**
     * @param reload
     * @return
     * @throws ServiceException
     */
    protected List<S3Bucket> getBuckets(boolean reload) throws IOException, ServiceException {
        if(buckets.isEmpty() || reload) {
            buckets.clear();
            if(host.getCredentials().isAnonymousLogin()) {
                log.info("Anonymous cannot list buckets");
                // Listing buckets not supported for thirdparty buckets
                String bucketname = this.getContainerForHostname(host.getHostname());
                if(StringUtils.isEmpty(bucketname)) {
                    if(StringUtils.isNotBlank(host.getDefaultPath())) {
                        Path d = PathFactory.createPath(this, host.getDefaultPath(), AbstractPath.DIRECTORY_TYPE);
                        while(!d.getParent().isRoot()) {
                            d = d.getParent();
                        }
                        bucketname = d.getName();
                    }
                }
                if(StringUtils.isEmpty(bucketname)) {
                    log.error("No bucket name given in hostname or default path");
                    return Collections.emptyList();
                }
                if(!this.getClient().isBucketAccessible(bucketname)) {
                    throw new IOException("Bucket not accessible: " + bucketname);
                }
                S3Bucket bucket = new S3Bucket(bucketname);
                try {
                    StorageOwner owner = this.getClient().getBucketAcl(bucketname).getOwner();
                    bucket.setOwner(owner);
                }
                catch(ServiceException e) {
                    // ACL not readable by anonymous user.
                    log.warn(e.getMessage());
                }
                buckets.put(bucketname, bucket);
            }
            else {
                // If bucketname is specified in hostname, try to connect to this particular bucket only.
                String bucketname = this.getContainerForHostname(host.getHostname());
                if(StringUtils.isNotEmpty(bucketname)) {
                    if(!this.getClient().isBucketAccessible(bucketname)) {
                        throw new IOException("Bucket not accessible: " + bucketname);
                    }
                    S3Bucket bucket = new S3Bucket(bucketname);
                    try {
                        StorageOwner owner = this.getClient().getBucketAcl(bucketname).getOwner();
                        bucket.setOwner(owner);
                    }
                    catch(ServiceException e) {
                        // ACL not readable by anonymous user.
                        log.warn(e.getMessage());
                    }
                    buckets.put(bucketname, bucket);
                }
                else {
                    // List all buckets owned
                    for(S3Bucket bucket : this.getClient().listAllBuckets()) {
                        buckets.put(bucket.getName(), bucket);
                    }
                }
            }
            if(reload) {
                loggingStatus.clear();
                versioningStatus.clear();
                for(Distribution.Method method : this.getSupportedDistributionMethods()) {
                    distributionStatus.get(method).clear();
                }
            }
        }
        return new ArrayList<S3Bucket>(buckets.values());
    }

    /**
     * @param bucketname
     * @return
     * @throws IOException
     */
    protected S3Bucket getBucket(final String bucketname) throws IOException {
        try {
            for(S3Bucket bucket : this.getBuckets(false)) {
                if(bucket.getName().equals(bucketname)) {
                    return bucket;
                }
            }
        }
        catch(ServiceException e) {
            this.error("Cannot read container configuration", e);
        }
        throw new ConnectionCanceledException("Bucket not found with name:" + bucketname);
    }

    /**
     * @return List of data center locations.
     */
    public static List<String> getAvailableLocations() {
        return Arrays.asList("US", S3Bucket.LOCATION_EUROPE, S3Bucket.LOCATION_US_WEST, S3Bucket.LOCATION_ASIA_PACIFIC);
    }

    /**
     * Set to false if permission error response indicates this
     * feature is not implemented.
     */
    private boolean bucketLocationSupported = true;

    public boolean isBucketLocationSupported() {
        return bucketLocationSupported;
    }

    protected void setBucketLocationSupported(boolean bucketLocationSupported) {
        this.bucketLocationSupported = bucketLocationSupported;
    }

    /**
     * Bucket geographical location
     *
     * @return
     */
    public String getLocation(final String container) {
        if(this.isBucketLocationSupported()) {
            try {
                final S3Bucket bucket = this.getBucket(container);
                if(bucket.isLocationKnown()) {
                    return bucket.getLocation();
                }
                if(this.getHost().getCredentials().isAnonymousLogin()) {
                    log.info("Anonymous cannot access bucket location");
                    return null;
                }
                this.check();
                String location = this.getClient().getBucketLocation(container);
                if(StringUtils.isBlank(location)) {
                    location = "US"; //Default location US is null
                }
                // Cache location
                bucket.setLocation(location);
                return location;
            }
            catch(ServiceException e) {
                log.warn("Bucket location not supported:" + e.getMessage());
                this.setBucketLocationSupported(false);
            }
            catch(IOException e) {
                this.error("Cannot read container configuration", e);
            }
        }
        return null;
    }

    @Override
    protected void connect() throws IOException {
        if(this.isConnected()) {
            return;
        }
        this.fireConnectionWillOpenEvent();

        // Configure connection options
        this.configure();

        // Prompt the login credentials first
        this.login();
        this.fireConnectionDidOpenEvent();
    }

    @Override
    protected void login(LoginController controller, Credentials credentials) throws IOException {
        try {
            this.S3 = new RequestEntityRestStorageService(credentials.isAnonymousLogin() ? null : new AWSCredentials(credentials.getUsername(),
                    credentials.getPassword()));
            this.getBuckets(true);
        }
        catch(ServiceException e) {
            if(this.isLoginFailure(e)) {
                this.message(Locale.localizedString("Login failed", "Credentials"));
                controller.fail(host.getProtocol(), credentials);
                this.login();
            }
            else {
                throw new IOException(e.getMessage());
            }
        }
    }

    /**
     * Prompt for MFA credentials
     *
     * @return MFA one time authentication password.
     */
    protected Credentials mfa(LoginController controller) throws ConnectionCanceledException {
        Credentials credentials = new Credentials(
                Preferences.instance().getProperty("s3.mfa.serialnumber"), null, false) {
            @Override
            public String getUsernamePlaceholder() {
                return Locale.localizedString("MFA Serial Number", "S3");
            }

            @Override
            public String getPasswordPlaceholder() {
                return Locale.localizedString("MFA Authentication Code", "S3");
            }
        };
        // Prompt for MFA credentials.
        controller.prompt(host.getProtocol(), credentials,
                Locale.localizedString("Provide additional login credentials", "Credentials"),
                Locale.localizedString("Multi-Factor Authentication", "S3"), false, false, false);

        Preferences.instance().setProperty("s3.mfa.serialnumber", credentials.getUsername());
        return credentials;
    }

    /**
     * Check for Invalid Access ID or Invalid Secret Key
     *
     * @param e
     * @return True if the error code of the S3 exception is a login failure
     */
    protected boolean isLoginFailure(ServiceException e) {
        if(403 == e.getResponseCode()) {
            return true;
        }
        if(null == e.getErrorCode()) {
            return false;
        }
        return e.getErrorCode().equals("InvalidAccessKeyId") // Invalid Access ID
                || e.getErrorCode().equals("SignatureDoesNotMatch"); // Invalid Secret Key
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
            S3 = null;
            this.fireConnectionDidCloseEvent();
        }
    }

    /**
     * @return True
     */
    @Override
    public boolean isDownloadResumable() {
        return true;
    }

    /**
     * @return No Content-Range support
     */
    @Override
    public boolean isUploadResumable() {
        return false;
    }

    @Override
    public boolean isAclSupported() {
        return true;
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
     * Renaming buckets is not currently supported by S3
     *
     * @return True if directory placeholder or object
     */
    @Override
    public boolean isRenameSupported(Path file) {
        return !file.attributes().isVolume();
    }

    /**
     * Amazon CloudFront Extension to create a new distribution configuration
     * *
     *
     * @param enabled Distribution status
     * @param bucket  Name of the container
     * @param cnames  DNS CNAME aliases for distribution
     * @param logging Access log configuration
     * @return Distribution configuration
     * @throws CloudFrontServiceException CloudFront failure details
     */
    protected org.jets3t.service.model.cloudfront.Distribution createDistribution(boolean enabled,
                                                                                  Distribution.Method method,
                                                                                  final String bucket,
                                                                                  String[] cnames,
                                                                                  LoggingStatus logging,
                                                                                  String defaultRootObject) throws CloudFrontServiceException {
        final long reference = System.currentTimeMillis();
        CloudFrontService cf = this.createCloudFrontService();
        if(method.equals(Distribution.STREAMING)) {
            return cf.createStreamingDistribution(
                    this.getHostnameForContainer(bucket),
                    String.valueOf(reference), // Caller reference - a unique string value
                    cnames, // CNAME aliases for distribution
                    new Date(reference).toString(), // Comment
                    enabled,  // Enabled?
                    logging
            );
        }
        return cf.createDistribution(
                this.getHostnameForContainer(bucket),
                String.valueOf(reference), // Caller reference - a unique string value
                cnames, // CNAME aliases for distribution
                new Date(reference).toString(), // Comment
                enabled,  // Enabled?
                logging, // Logging Status. Disabled if null
                null,
                false,
                null,
                null,
                defaultRootObject
        );
    }

    /**
     * Amazon CloudFront Extension used to enable or disable a distribution configuration and its CNAMESs
     *
     * @param enabled Distribution status
     * @param id      Distribution reference
     * @param cnames  DNS CNAME aliases for distribution
     * @param logging Access log configuration
     * @throws CloudFrontServiceException CloudFront failure details
     */
    protected void updateDistribution(boolean enabled, Distribution.Method method,
                                      String id,
                                      String[] cnames, LoggingStatus logging, String defaultRootObject) throws CloudFrontServiceException {
        final long reference = System.currentTimeMillis();
        CloudFrontService cf = this.createCloudFrontService();
        if(method.equals(Distribution.STREAMING)) {
            cf.updateStreamingDistributionConfig(
                    id,
                    cnames, // CNAME aliases for distribution
                    new Date(reference).toString(), // Comment
                    enabled, // Enabled?
                    logging
            );
        }
        else {
            cf.updateDistributionConfig(
                    id,
                    cnames, // CNAME aliases for distribution
                    new Date(reference).toString(), // Comment
                    enabled, // Enabled?
                    logging, // Logging Status. Disabled if null
                    null,
                    false,
                    null,
                    null,
                    defaultRootObject
            );
        }
    }

    /**
     * You can make any number of invalidation requests, but you can have only three invalidation requests
     * in progress at one time. Each request can contain up to 1,000 objects to invalidate. If you
     * exceed these limits, you get an error message.
     * <p/>
     * It usually takes 10 to 15 minutes to complete your invalidation request, depending on
     * the size of your request.
     *
     * @param bucket
     * @param method
     * @param files
     * @throws CloudFrontServiceException
     */
    public void invalidateDistributionObjects(String bucket, Distribution.Method method, List<Path> files) {
        try {
            final long reference = System.currentTimeMillis();
            Distribution d = this.getDistribution(bucket, method);
            if(null == d) {
                log.warn("No cached distribution");
                return;
            }
            List<String> keys = this.getInvalidationKeys(files);
            if(keys.isEmpty()) {
                log.warn("No keys selected for invalidation");
                return;
            }
            CloudFrontService cf = this.createCloudFrontService();
            cf.invalidateObjects(d.getId(),
                    keys.toArray(new String[keys.size()]), // objects
                    new Date(reference).toString() // Comment
            );
        }
        catch(CloudFrontServiceException e) {
            this.error("Cannot write CDN configuration", e);
        }
        finally {
            distributionStatus.get(method).clear();
        }
    }

    private List<String> getInvalidationKeys(List<Path> files) {
        List<String> keys = new ArrayList<String>();
        for(Path file : files) {
            if(file.attributes().isDirectory()) {
                keys.addAll(this.getInvalidationKeys(file.<Path>children()));
            }
            else {
                keys.add(((S3Path) file).getKey());
            }
        }
        return keys;
    }

    /**
     * @param distribution
     * @return
     */
    protected String readInvalidationStatus(Distribution distribution) {
        if(this.getHost().getCredentials().isAnonymousLogin()) {
            log.info("Anonymous cannot write distribution");
            return Locale.localizedString("None");
        }
        try {
            final long reference = System.currentTimeMillis();
            CloudFrontService cf = this.createCloudFrontService();
            boolean complete = false;
            int inprogress = 0;
            List<InvalidationSummary> summaries = cf.listInvalidations(distribution.getId());
            for(InvalidationSummary s : summaries) {
                if("Completed".equals(s.getStatus())) {
                    // No schema for status enumeration. Fail.
                    complete = true;
                }
                else {
                    // InProgress
                    inprogress++;
                }
            }
            if(inprogress > 0) {
                return MessageFormat.format(Locale.localizedString("{0} invalidations in progress", "S3"), inprogress);
            }
            if(complete) {
                return MessageFormat.format(Locale.localizedString("{0} invalidations completed", "S3"), summaries.size());
            }
        }
        catch(CloudFrontServiceException e) {
            this.error("Cannot read CDN configuration", e);
        }
        return Locale.localizedString("None");
    }

    /**
     * Amazon CloudFront Extension used to list all configured distributions
     *
     * @param bucket Name of the container
     * @param method
     * @return All distributions for the given AWS Credentials
     * @throws CloudFrontServiceException CloudFront failure details
     */
    protected org.jets3t.service.model.cloudfront.Distribution[] listDistributions(String bucket,
                                                                                   Distribution.Method method) throws CloudFrontServiceException {
        CloudFrontService cf = this.createCloudFrontService();
        if(method.equals(Distribution.STREAMING)) {
            return cf.listStreamingDistributions(bucket);
        }
        return cf.listDistributions(bucket);
    }

    /**
     * @param distribution Distribution configuration
     * @throws CloudFrontServiceException CloudFront failure details
     * @returann
     */
    protected DistributionConfig getDistributionConfig(final org.jets3t.service.model.cloudfront.Distribution distribution) throws CloudFrontServiceException {
        CloudFrontService cf = this.createCloudFrontService();
        if(distribution.isStreamingDistribution()) {
            return cf.getStreamingDistributionConfig(distribution.getId());
        }
        return cf.getDistributionConfig(distribution.getId());
    }

    /**
     * @param distribution A distribution (the distribution must be disabled and deployed first)
     * @throws CloudFrontServiceException CloudFront failure details
     */
    protected void deleteDistribution(final org.jets3t.service.model.cloudfront.Distribution distribution) throws CloudFrontServiceException {
        CloudFrontService cf = this.createCloudFrontService();
        if(distribution.isStreamingDistribution()) {
            cf.deleteStreamingDistribution(distribution.getId());
        }
        else {
            cf.deleteDistribution(distribution.getId());
        }
    }

    /**
     * Cached instance for session
     */
    private CloudFrontService cloudfront;

    /**
     * Amazon CloudFront Extension
     *
     * @return A cached cloud front service interface
     * @throws CloudFrontServiceException CloudFront failure
     */
    private CloudFrontService createCloudFrontService() throws CloudFrontServiceException {
        if(null == cloudfront) {
            final Credentials credentials = host.getCredentials();

            // Construct a CloudFrontService object to interact with the service.
            HostConfiguration hostconfig = null;
            try {
                hostconfig = new StickyHostConfiguration();
                final HttpHost endpoint = new HttpHost(new URI(CloudFrontService.ENDPOINT, false));
                hostconfig.setHost(endpoint.getHostName(), endpoint.getPort(),
                        new org.apache.commons.httpclient.protocol.Protocol(endpoint.getProtocol().getScheme(),
                                new SSLSocketFactory(this.getTrustManager(endpoint.getHostName())), endpoint.getPort())
                );
            }
            catch(URIException e) {
                log.error(e.getMessage(), e);
            }
            cloudfront = new CloudFrontService(
                    new AWSCredentials(credentials.getUsername(), credentials.getPassword()),
                    this.getUserAgent(), // Invoking application description
                    null, // Credentials Provider
                    new Jets3tProperties(),
                    hostconfig);
        }
        return cloudfront;
    }

    /**
     * Cache distribution status result.
     */
    private Map<Distribution.Method, Map<String, Distribution>> distributionStatus
            = new HashMap<Distribution.Method, Map<String, Distribution>>();

    /**
     * @param container
     * @param method
     * @return
     */
    @Override
    public Distribution getDistribution(String container, Distribution.Method method) {
        return distributionStatus.get(method).get(container);
    }

    /**
     * @return
     */
    @Override
    public Distribution readDistribution(String container, Distribution.Method method) {
        if(this.getHost().getCredentials().isAnonymousLogin()) {
            log.info("Anonymous cannot read distribution");
            return new Distribution();
        }
        if(this.getSupportedDistributionMethods().size() == 0) {
            return new Distribution();
        }
        if(!distributionStatus.get(method).containsKey(container)) {
            try {
                this.check();
                for(org.jets3t.service.model.cloudfront.Distribution d : this.listDistributions(container, method)) {
                    // Retrieve distribution's configuration to access current logging status settings.
                    final DistributionConfig distributionConfig = this.getDistributionConfig(d);
                    // We currently only support one distribution per bucket
                    final Distribution distribution = new Distribution(d.getId(), d.isEnabled(), d.isDeployed(),
                            method.getProtocol() + d.getDomainName() + method.getContext(),
                            Locale.localizedString(d.getStatus(), "S3"),
                            distributionConfig.getCNAMEs(),
                            distributionConfig.isLoggingEnabled(),
                            method, distributionConfig.getDefaultRootObject());
                    distribution.setInvalidationStatus(this.readInvalidationStatus(distribution));
                    if(distribution.isDeployed()) {
                        distributionStatus.get(method).put(container, distribution);
                    }
                    // We currently only support one distribution per bucket
                    return distribution;
                }
            }
            catch(CloudFrontServiceException e) {
                log.warn("Invalid CloudFront account:" + e.getMessage());
                this.setSupportedDistributionMethods(Collections.<Distribution.Method>emptyList());
            }
            catch(IOException e) {
                this.error("Cannot read CDN configuration", e);
            }
        }
        if(distributionStatus.get(method).containsKey(container)) {
            return distributionStatus.get(method).get(container);
        }
        return new Distribution();
    }

    /**
     * Amazon CloudFront Extension
     *
     * @param enabled
     * @param method
     * @param cnames
     * @param logging
     */
    @Override
    public void writeDistribution(boolean enabled, String container, Distribution.Method method,
                                  String[] cnames, boolean logging, String defaultRootObject) {
        if(this.getHost().getCredentials().isAnonymousLogin()) {
            log.info("Anonymous cannot write distribution");
            return;
        }
        if(this.getSupportedDistributionMethods().size() == 0) {
            return;
        }
        try {
            LoggingStatus l = null;
            if(logging) {
                l = new LoggingStatus(this.getHostnameForContainer(container),
                        Preferences.instance().getProperty("cloudfront.logging.prefix"));
            }
            this.check();
            StringBuilder name = new StringBuilder(Locale.localizedString("Amazon CloudFront", "S3")).append(" ").append(method.toString());
            if(enabled) {
                this.message(MessageFormat.format(Locale.localizedString("Enable {0} Distribution", "Status"), name));
            }
            else {
                this.message(MessageFormat.format(Locale.localizedString("Disable {0} Distribution", "Status"), name));
            }
            Distribution d = this.getDistribution(container, method);
            if(null == d) {
                // No existing distribution found for method. Create new configuration.
                this.createDistribution(enabled, method, container, cnames, l, defaultRootObject);
            }
            else {
                boolean modified = false;
                if(d.isEnabled() != enabled) {
                    modified = true;
                }
                if(!Arrays.equals(d.getCNAMEs(), cnames)) {
                    modified = true;
                }
                if(d.isLogging() != logging) {
                    modified = true;
                }

                if(null == d.getDefaultRootObject() && null == defaultRootObject) {
                    // No change to default root object
                }
                else if(null != d.getDefaultRootObject() && null == defaultRootObject) {
                    modified = true;
                }
                else if(null == d.getDefaultRootObject() && null != defaultRootObject) {
                    modified = true;
                }
                else if(!d.getDefaultRootObject().equals(defaultRootObject)) {
                    modified = true;
                }

                if(modified) {
                    this.updateDistribution(enabled, method, d.getId(), cnames, l, defaultRootObject);
                }
                else {
                    log.info("Skip updating distribution not modified.");
                }
            }
        }
        catch(CloudFrontServiceException e) {
            this.error("Cannot write CDN configuration", e);
        }
        catch(IOException e) {
            this.error("Cannot write CDN configuration", e);
        }
        finally {
            distributionStatus.get(method).clear();
        }
    }

    @Override
    public String getDistributionServiceName() {
        return Locale.localizedString("Amazon CloudFront", "S3");
    }

    private List<Distribution.Method> distributionMethods
            = Arrays.asList(Distribution.DOWNLOAD, Distribution.STREAMING);

    {
        for(Distribution.Method method : this.getSupportedDistributionMethods()) {
            distributionStatus.put(method, new HashMap<String, Distribution>(0));
        }
    }

    @Override
    public List<Distribution.Method> getSupportedDistributionMethods() {
        return distributionMethods;
    }

    private void setSupportedDistributionMethods(List<Distribution.Method> distributionMethods) {
        this.distributionMethods = distributionMethods;
    }

    private List<String> storageClasses
            = Arrays.asList(S3Object.STORAGE_CLASS_STANDARD, S3Object.STORAGE_CLASS_REDUCED_REDUNDANCY);

    @Override
    public List<String> getSupportedStorageClasses() {
        return storageClasses;
    }

    private void setSupportedStorageClasses(List<String> storageClasses) {
        this.storageClasses = storageClasses;
    }

    /**
     * Set to false if permission error response indicates this
     * feature is not implemented.
     */
    private boolean loggingSupported = true;

    /**
     * @return True if the service supports bucket logging.
     */
    public boolean isLoggingSupported() {
        return loggingSupported;
    }

    protected void setLoggingSupported(boolean loggingSupported) {
        this.loggingSupported = loggingSupported;
    }

    /**
     * Cache versioning status result.
     */
    private Map<String, S3BucketLoggingStatus> loggingStatus
            = new HashMap<String, S3BucketLoggingStatus>();

    /**
     * @param container The bucket name
     * @return True if the bucket logging status is enabled.
     */
    public boolean isLogging(final String container) {
        if(this.isLoggingSupported()) {
            if(!loggingStatus.containsKey(container)) {
                try {
                    if(this.getHost().getCredentials().isAnonymousLogin()) {
                        log.info("Anonymous cannot access logging status");
                        return false;
                    }
                    this.check();
                    final S3BucketLoggingStatus status
                            = this.getClient().getBucketLoggingStatus(container);
                    loggingStatus.put(container, status);
                }
                catch(ServiceException e) {
                    log.warn("Bucket logging not supported:" + e.getMessage());
                    this.setLoggingSupported(false);
                }
                catch(IOException e) {
                    this.error("Cannot read container configuration", e);
                }
            }
            if(loggingStatus.containsKey(container)) {
                return loggingStatus.get(container).isLoggingEnabled();
            }
        }
        return false;
    }

    /**
     * @param container The bucket name
     * @param enabled
     */
    public void setLogging(final String container, final boolean enabled) {
        if(this.isLoggingSupported()) {
            try {
                // Logging target bucket
                final S3BucketLoggingStatus status = new S3BucketLoggingStatus(container, null);
                if(enabled) {
                    status.setLogfilePrefix(Preferences.instance().getProperty("s3.logging.prefix"));
                }
                this.check();
                this.getClient().setBucketLoggingStatus(container, status, true);
            }
            catch(ServiceException e) {
                this.error("Cannot write file attributes", e);
            }
            catch(IOException e) {
                this.error("Cannot write file attributes", e);
            }
            finally {
                loggingStatus.remove(container);
            }
        }
    }

    /**
     * Set to false if permission error response indicates this
     * feature is not implemented.
     */
    private boolean versioningSupported = true;

    /**
     * @return True if the service supports object versioning.
     */
    public boolean isVersioningSupported() {
        return versioningSupported;
    }

    @Override
    public boolean isRevertSupported() {
        return this.isVersioningSupported();
    }

    /**
     * @param versioningSupported
     */
    protected void setVersioningSupported(boolean versioningSupported) {
        this.versioningSupported = versioningSupported;
    }

    /**
     * Cache versioning status result.
     */
    private Map<String, S3BucketVersioningStatus> versioningStatus
            = new HashMap<String, S3BucketVersioningStatus>();

    /**
     * @param container The bucket name
     * @return
     */
    public boolean isVersioning(final String container) {
        if(this.isVersioningSupported()) {
            if(!versioningStatus.containsKey(container)) {
                try {
                    this.check();
                    final S3BucketVersioningStatus status
                            = this.getClient().getBucketVersioningStatus(container);
                    versioningStatus.put(container, status);
                }
                catch(ServiceException e) {
                    log.warn("Bucket versioning not supported:" + e.getMessage());
                    this.setVersioningSupported(false);
                }
                catch(IOException e) {
                    this.error("Cannot read container configuration", e);
                }
            }
            if(versioningStatus.containsKey(container)) {
                return versioningStatus.get(container).isVersioningEnabled();
            }
        }
        return false;
    }

    /**
     * @param container The bucket name
     * @return True if MFA is required to delete objects in this bucket.
     */
    public boolean isMultiFactorAuthentication(final String container) {
        if(this.isVersioning(container)) {
            return versioningStatus.get(container).isMultiFactorAuthDeleteRequired();
        }
        return false;
    }

    /**
     * @param container  The bucket name
     * @param mfa
     * @param versioning
     */
    public void setVersioning(final String container, boolean mfa, boolean versioning) {
        if(this.isVersioningSupported()) {
            try {
                this.check();
                if(this.isMultiFactorAuthentication(container)) {
                    // The bucket is already MFA protected.
                    LoginController c = LoginControllerFactory.instance(this);
                    final Credentials auth = this.mfa(c);
                    if(versioning) {
                        if(this.isVersioning(container)) {
                            log.debug("Versioning already enabled for bucket " + container);
                        }
                        else {
                            // Enable versioning if not already active.
                            log.debug("Enable bucket versioning with MFA " + auth.getUsername() + " for " + container);
                            this.getClient().enableBucketVersioningWithMFA(container,
                                    auth.getUsername(), auth.getPassword());
                        }
                    }
                    else {
                        log.debug("Suspend bucket versioning with MFA " + auth.getUsername() + " for " + container);
                        this.getClient().suspendBucketVersioningWithMFA(container,
                                auth.getUsername(), auth.getPassword());
                    }
                    if(versioning && !mfa) {
                        log.debug("Disable MFA " + auth.getUsername() + " for " + container);
                        // User has choosen to disable MFA
                        final Credentials auth2 = this.mfa(c);
                        this.getClient().disableMFAForVersionedBucket(container,
                                auth2.getUsername(), auth2.getPassword());
                    }
                }
                else {
                    if(versioning) {
                        if(mfa) {
                            LoginController c = LoginControllerFactory.instance(this);
                            final Credentials auth = this.mfa(c);
                            log.debug("Enable bucket versioning with MFA " + auth.getUsername() + " for " + container);
                            this.getClient().enableBucketVersioningWithMFA(container,
                                    auth.getUsername(), auth.getPassword());
                        }
                        else {
                            if(this.isVersioning(container)) {
                                log.debug("Versioning already enabled for bucket " + container);
                            }
                            else {
                                log.debug("Enable bucket versioning for " + container);
                                this.getClient().enableBucketVersioning(container);
                            }
                        }
                    }
                    else {
                        log.debug("Susped bucket versioning for " + container);
                        this.getClient().suspendBucketVersioning(container);
                    }
                }
            }
            catch(ServiceException e) {
                this.error("Cannot write file attributes", e);
            }
            catch(IOException e) {
                this.error("Cannot write file attributes", e);
            }
            finally {
                versioningStatus.remove(container);
            }
        }
    }

    /**
     * Set to false if permission error response indicates this
     * feature is not implemented.
     */
    private boolean requesterPaysSupported = true;

    public boolean isRequesterPaysSupported() {
        return requesterPaysSupported;
    }

    protected void setRequesterPaysSupported(boolean requesterPaysSupported) {
        this.requesterPaysSupported = requesterPaysSupported;
    }

    /**
     * @param container The bucket name
     * @param enabled
     */
    public void setRequesterPays(final String container, boolean enabled) {
        if(this.isRequesterPaysSupported()) {
            try {
                this.check();
                this.getClient().setRequesterPaysBucket(container, enabled);
            }
            catch(ServiceException e) {
                this.error("Cannot write file attributes", e);
            }
            catch(IOException e) {
                this.error("Cannot write file attributes", e);
            }
        }
    }

    /**
     * @param container The bucket name
     * @return
     */
    public boolean isRequesterPays(final String container) {
        if(this.isRequesterPaysSupported()) {
            try {
                this.check();
                return this.getClient().isRequesterPaysBucket(container);
            }
            catch(ServiceException e) {
                this.error("Cannot read container configuration", e);
            }
            catch(IOException e) {
                this.error("Cannot read container configuration", e);
            }
        }
        return false;
    }

    @Override
    public List<Acl.Role> getAvailableAclRoles(List<Path> files) {
        return Arrays.asList(new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_FULL_CONTROL.toString()),
                new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_READ.toString()),
                new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_WRITE.toString()),
                new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_READ_ACP.toString()),
                new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_WRITE_ACP.toString()));
    }

    @Override
    public List<Acl.User> getAvailableAclUsers() {
        final List<Acl.User> users = new ArrayList<Acl.User>(Arrays.asList(
                new Acl.CanonicalUser(),
                new Acl.GroupUser(GroupGrantee.ALL_USERS.getIdentifier(), false),
                new Acl.EmailUser() {
                    @Override
                    public String getPlaceholder() {
                        return Locale.localizedString("Amazon Customer Email Address", "S3");
                    }
                })
        );
        for(final S3Bucket container : buckets.values()) {
            final StorageOwner owner = container.getOwner();
            if(null == owner) {
                log.warn("Owner not known for container " + container);
                continue;
            }
            final Acl.CanonicalUser canonicalUser = new Acl.CanonicalUser(owner.getId(), owner.getDisplayName(), false) {
                @Override
                public String getPlaceholder() {
                    return owner.getDisplayName() + " (" + Locale.localizedString("Owner") + ")";
                }
            };
            if(users.contains(canonicalUser)) {
                continue;
            }
            users.add(0, canonicalUser);
        }
        return users;
    }

    @Override
    public Acl getPrivateAcl(String container) {
        for(final S3Bucket bucket : buckets.values()) {
            if(bucket.getName().equals(container)) {
                StorageOwner owner = bucket.getOwner();
                if(null == owner) {
                    log.warn("Owner not known for container " + container);
                    continue;
                }
                return new Acl(new Acl.CanonicalUser(owner.getId()),
                        new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_FULL_CONTROL.toString()));
            }
        }
        log.warn("No such container:" + container);
        return new Acl();
    }

    @Override
    public Acl getPublicAcl(String container, boolean readable, boolean writable) {
        Acl acl = this.getPrivateAcl(container);
        if(readable) {
            acl.addAll(new Acl.GroupUser(GroupGrantee.ALL_USERS.getIdentifier()),
                    new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_READ.toString()));
        }
        if(writable) {
            acl.addAll(new Acl.GroupUser(GroupGrantee.ALL_USERS.getIdentifier()),
                    new Acl.Role(org.jets3t.service.acl.Permission.PERMISSION_WRITE.toString()));
        }
        return acl;
    }
}