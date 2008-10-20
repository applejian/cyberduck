package ch.cyberduck.core;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
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

import com.apple.cocoa.foundation.NSDictionary;
import com.apple.cocoa.foundation.NSMutableDictionary;

import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.s3.S3Session;
import ch.cyberduck.ui.cocoa.CDMainApplication;
import ch.cyberduck.ui.cocoa.growl.Growl;
import ch.cyberduck.ui.cocoa.threading.DefaultMainAction;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @version $Id$
 */
public class UploadTransfer extends Transfer {

    public UploadTransfer(Path root) {
        super(root);
    }

    public UploadTransfer(List<Path> roots) {
        super(roots);
    }

    public UploadTransfer(NSDictionary dict, Session s) {
        super(dict, s);
    }

    public NSMutableDictionary getAsDictionary() {
        NSMutableDictionary dict = super.getAsDictionary();
        dict.setObjectForKey(String.valueOf(TransferFactory.KIND_UPLOAD), "Kind");
        return dict;
    }

    protected void init() {
        log.debug("init");
        this.bandwidth = new BandwidthThrottle(
                Preferences.instance().getFloat("queue.upload.bandwidth.bytes"));
    }

    /**
     *
     */
    private abstract class UploadTransferFilter extends TransferFilter {
        public boolean accept(final Path file) {
            return UploadTransfer.this.exists(file.getLocal());
        }

        public void prepare(Path p) {
            if(p.attributes.isFile()) {
                // Read file size
                size += p.getLocal().attributes.getSize();
                if(p.getStatus().isResume()) {
                    transferred += p.attributes.getSize();
                }
            }
            if(p.attributes.isDirectory()) {
                if(!UploadTransfer.this.exists(p)) {
                    p.cache().put(p, new AttributedList<Path>(Collections.<Path>emptyList()));
                }
            }
        }
    }

    /**
     * A compiled representation of a regular expression.
     */
    private Pattern UPLOAD_SKIP_PATTERN = null;

    {
        try {
            UPLOAD_SKIP_PATTERN = Pattern.compile(
                    Preferences.instance().getProperty("queue.upload.skip.regex"));
        }
        catch(PatternSyntaxException e) {
            log.warn(e.getMessage());
        }
    }

    private final PathFilter<Local> childFilter = new PathFilter<Local>() {
        public boolean accept(Local child) {
            if(Preferences.instance().getBoolean("queue.upload.skip.enable")
                    && UPLOAD_SKIP_PATTERN.matcher(child.getName()).matches()) {
                return false;
            }
            return true;
        }
    };

    private final Cache<Path> _cache = new Cache<Path>();

    public AttributedList<Path> childs(final Path parent) {
        if(!this.exists(parent.getLocal())) {
            // Cannot fetch file listing of non existant file
            _cache.put(parent, new AttributedList<Path>(Collections.<Path>emptyList()));
        }
        if(!_cache.containsKey(parent)) {
            AttributedList<Path> childs = new AttributedList<Path>();
            for(AbstractPath local : parent.getLocal().childs(new NullComparator<Local>(),
                    childFilter)) {
                final Path child = PathFactory.createPath(parent.getSession(),
                        parent.getAbsolute(),
                        new Local(local.getAbsolute()));
                child.getStatus().setSkipped(parent.getStatus().isSkipped());
                childs.add(child);
            }
            _cache.put(parent, childs);
        }
        return _cache.get(parent);
    }

    public boolean isCached(Path file) {
        return _cache.containsKey(file);
    }

    public boolean isResumable() {
        if(this.getSession() instanceof S3Session) {
            return false;
        }
        return super.isResumable();
    }

    private final TransferFilter ACTION_OVERWRITE = new UploadTransferFilter() {
        public boolean accept(final Path p) {
            if(super.accept(p)) {
                if(p.attributes.isDirectory()) {
                    // Do not attempt to create a directory that already exists
                    return !UploadTransfer.this.exists(p);
                }
                return true;
            }
            return false;
        }

        public void prepare(final Path p) {
            if(p.attributes.isFile()) {
                p.getStatus().setResume(false);
            }
            super.prepare(p);
        }

    };

    private final TransferFilter ACTION_RESUME = new UploadTransferFilter() {
        public boolean accept(final Path p) {
            if(super.accept(p)) {
                if(p.getStatus().isComplete() || p.getLocal().attributes.getSize() == p.attributes.getSize()) {
                    // No need to resume completed transfers
                    p.getStatus().setComplete(true);
                    return false;
                }
                if(p.attributes.isDirectory()) {
                    return !UploadTransfer.this.exists(p);
                }
                return true;
            }
            return false;
        }

        public void prepare(final Path p) {
            if(UploadTransfer.this.exists(p)) {
                if(p.attributes.getSize() == -1) {
                    p.readSize();
                }
                if(p.attributes.getModificationDate() == -1) {
                    if(Preferences.instance().getBoolean("queue.upload.preserveDate")) {
                        p.readTimestamp();
                    }
                }
                if(p.attributes.getPermission() == null) {
                    if(Preferences.instance().getBoolean("queue.upload.changePermissions")) {
                        p.readPermission();
                    }
                }
            }
            if(p.attributes.isFile()) {
                // Append to file if size is not zero
                final boolean resume = UploadTransfer.this.exists(p)
                        && p.attributes.getSize() > 0;
                p.getStatus().setResume(resume);
                if(p.getStatus().isResume()) {
                    p.getStatus().setCurrent(p.attributes.getSize());
                }
            }
            super.prepare(p);
        }
    };

    private final TransferFilter ACTION_RENAME = new UploadTransferFilter() {
        public boolean accept(final Path p) {
            // Rename every file
            return super.accept(p);
        }

        public void prepare(final Path p) {
            if(UploadTransfer.this.exists(p)) {
                final String parent = p.getParent().getAbsolute();
                final String filename = p.getName();
                String proposal = filename;
                int no = 0;
                int index = filename.lastIndexOf(".");
                while(p.exists()) { // Do not use cached value of exists!
                    no++;
                    if(index != -1 && index != 0) {
                        proposal = filename.substring(0, index)
                                + "-" + no + filename.substring(index);
                    }
                    else {
                        proposal = filename + "-" + no;
                    }
                    p.setPath(parent, proposal);
                }
                log.info("Changed local name to:" + p.getName());
            }
            if(p.attributes.isFile()) {
                p.getStatus().setResume(false);
            }
            super.prepare(p);
        }
    };

    private final TransferFilter ACTION_SKIP = new UploadTransferFilter() {
        public boolean accept(final Path p) {
            if(super.accept(p)) {
                if(!UploadTransfer.this.exists(p)) {
                    return true;
                }
            }
            return false;
        }
    };

    public TransferFilter filter(final TransferAction action) {
        log.debug("filter:"+action);
        if(action.equals(TransferAction.ACTION_OVERWRITE)) {
            return ACTION_OVERWRITE;
        }
        if(action.equals(TransferAction.ACTION_RESUME)) {
            return ACTION_RESUME;
        }
        if(action.equals(TransferAction.ACTION_RENAME)) {
            return ACTION_RENAME;
        }
        if(action.equals(TransferAction.ACTION_SKIP)) {
            return ACTION_SKIP;
        }
        if(action.equals(TransferAction.ACTION_CALLBACK)) {
            for(Path root: this.getRoots()) {
                if(this.exists(root)) {
                    if(root.getLocal().attributes.isDirectory()) {
                        if(0 == root.childs().size()) {
                            // Do not prompt for existing empty directories
                            continue;
                        }
                    }
                    // Prompt user to choose a filter
                    TransferAction result = prompt.prompt();
                    return this.filter(result); //break out of loop
                }
            }
            // No files exist yet therefore it is most straightforward to use the overwrite action
            return this.filter(TransferAction.ACTION_OVERWRITE);
        }
        return super.filter(action);
    }

    protected void clear(final TransferOptions options) {
        _cache.clear();
        super.clear(options);
    }

    public TransferAction action(final boolean resumeRequested, final boolean reloadRequested) {
        log.debug("action:"+resumeRequested+","+reloadRequested);
        if(resumeRequested) {
            // Force resume
            return TransferAction.ACTION_RESUME;
        }
        if(reloadRequested) {
            return TransferAction.forName(
                    Preferences.instance().getProperty("queue.upload.reload.fileExists")
            );
        }
        // Use default
        return TransferAction.forName(
                Preferences.instance().getProperty("queue.upload.fileExists")
        );
    }

    protected void _transferImpl(final Path p) {
        Permission permission = null;
        if(Preferences.instance().getBoolean("queue.upload.changePermissions")) {
            permission = p.attributes.getPermission();
            if(null == permission) {
                if(Preferences.instance().getBoolean("queue.upload.permissions.useDefault")) {
                    if(p.attributes.isFile()) {
                        permission = new Permission(
                                Preferences.instance().getInteger("queue.upload.permissions.file.default"));
                    }
                    if(p.attributes.isDirectory()) {
                        permission = new Permission(
                                Preferences.instance().getInteger("queue.upload.permissions.folder.default"));
                    }
                }
                else {
                    permission = p.getLocal().attributes.getPermission();
                }
            }
        }
        p.upload(bandwidth, new AbstractStreamListener() {
            public void bytesSent(long bytes) {
                transferred += bytes;
            }
        }, permission);
    }

    protected void fireTransferDidEnd() {
        if(this.isReset() && this.isComplete() && !this.isCanceled() && !(this.getTransferred() == 0)) {
            CDMainApplication.invoke(new DefaultMainAction() {
                public void run() {
                    Growl.instance().notify("Upload complete", getName());
                }
            }, true);
        }
        super.fireTransferDidEnd();
    }    
}