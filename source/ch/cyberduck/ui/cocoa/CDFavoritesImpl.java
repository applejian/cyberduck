package ch.cyberduck.ui.cocoa;

/*
 *  Copyright (c) 2003 David Kocher. All rights reserved.
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

import ch.cyberduck.core.Favorites;
import ch.cyberduck.core.Host;
import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.*;
import java.io.File;
import org.apache.log4j.Logger;

import java.util.Iterator;

/**
* @version $Id$
 */
public class CDFavoritesImpl extends Favorites {
    private static Logger log = Logger.getLogger(CDFavoritesImpl.class);

    private static Favorites instance;

    private static final File FAVORTIES_FILE = new File(NSPathUtilities.stringByExpandingTildeInPath("~/Library/Application Support/Cyberduck/Favorites.plist"));

    static {
	FAVORTIES_FILE.getParentFile().mkdir();
    }

    private CDFavoritesImpl() {
	super();
    }

    public static Favorites instance() {
	if(null == instance) {
	    instance = new CDFavoritesImpl();
	}
	return instance;
    }

    public void save() {
	log.debug("save");
	try {
	    Iterator i = super.getIterator();

	    NSMutableArray data = new NSMutableArray();
	    while(i.hasNext()) {
		data.addObject(i.next().toString());
	    }
	    NSMutableData collection = new NSMutableData();
	    collection.appendData(NSPropertyListSerialization.XMLDataFromPropertyList(data));

	    // data is written to a backup location, and then, assuming no errors occur, the backup location is renamed to the specified name
	    if(collection.writeToURL(FAVORTIES_FILE.toURL(), true))
		log.info("Favorites sucessfully saved in :"+FAVORTIES_FILE.toString());
	    else
		log.error("Error saving Favorites in :"+FAVORTIES_FILE.toString());
	}
	catch(java.net.MalformedURLException e) {
	    log.error(e.getMessage());
	}
    }

    public void load() {
	log.debug("load");
	if(FAVORTIES_FILE.exists()) {
	    log.info("Found Favorites file: "+FAVORTIES_FILE.toString());

	    NSData plistData = new NSData(FAVORTIES_FILE);
	    NSArray entries = (NSArray)NSPropertyListSerialization.propertyListFromXMLData(plistData);
	    log.info("Successfully read Favorites: "+entries);
	    java.util.Enumeration i = entries.objectEnumerator();
	    while(i.hasMoreElements()) {
		this.addItem((String)i.nextElement());
	    }
	}
    }
}
