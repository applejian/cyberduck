package ch.cyberduck.ui.cocoa.delegate;

/*
 *  Copyright (c) 2007 David Kocher. All rights reserved.
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

import com.apple.cocoa.application.NSMenuItem;

import ch.cyberduck.ui.cocoa.CDBrowserController;

import java.util.List;

/**
 * @version $Id:$
 */
public class BackPathHistoryMenuDelegate extends PathHistoryMenuDelegate {

    public BackPathHistoryMenuDelegate(CDBrowserController controller) {
        super(controller);
    }

    public List getHistory() {
        return controller.getBackHistory();
    }

    public void clearMenuItemClicked(NSMenuItem sender) {
        controller.clearBackHistory();
    }
}
