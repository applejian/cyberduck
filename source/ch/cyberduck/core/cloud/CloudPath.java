package ch.cyberduck.core.cloud;

/*
 *  Copyright (c) 2008 David Kocher. All rights reserved.
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

import ch.cyberduck.core.AbstractPath;
import ch.cyberduck.core.Local;
import ch.cyberduck.core.Path;

import org.apache.log4j.Logger;

/**
 * @version $Id$
 */
public abstract class CloudPath extends Path {
    private static Logger log = Logger.getLogger(CloudPath.class);

    public CloudPath(NSDictionary dict) {
        super(dict);
    }

    protected CloudPath(String parent, String name, int type) {
        super(parent, name, type);
    }

    protected CloudPath(String path, int type) {
        super(path, type);
    }

    protected CloudPath(String parent, final Local local) {
        super(parent, local);
    }

    public boolean isContainer() {
        return this.getParent().isRoot();
    }

    /**
     * @return The parent container/bucket of this file
     */
    public String getContainerName() {
        if(this.isRoot()) {
            return null;
        }
        AbstractPath bucketname = this;
        while(!bucketname.getParent().isRoot()) {
            bucketname = bucketname.getParent();
        }
        return bucketname.getName();
    }

    /**
     * @return Absolute path without the container name
     */
    public String getKey() {
        if(this.isContainer()) {
            return null;
        }
        if(this.getAbsolute().startsWith(Path.DELIMITER + this.getContainerName())) {
            return this.getAbsolute().substring(this.getContainerName().length() + 2);
        }
        return null;
    }

    /**
     * @param enabled
     * @param cnames
     * @param logging
     */
    public abstract void writeDistribution(final boolean enabled, final String[] cnames, boolean logging);

    /**
     * @return
     */
    public abstract Distribution readDistribution();
}
