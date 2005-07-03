package ch.cyberduck.ui.cocoa;

import com.apple.cocoa.application.NSAlertPanel;
import com.apple.cocoa.application.NSApplication;
import com.apple.cocoa.application.NSWindow;
import com.apple.cocoa.foundation.*;

import java.io.File;

import org.apache.log4j.Logger;

import ch.cyberduck.core.Host;

public class CDDotMacController {

    private static Logger log = Logger.getLogger(CDDotMacController.class);

    static {
        // Ensure native odb library is loaded
        try {
            NSBundle bundle = NSBundle.mainBundle();
            String lib = bundle.resourcePath() + "/Java/" + "libDotMac.dylib";
            log.info("Locating libDotMac.dylib at '" + lib + "'");
            System.load(lib);
        }
        catch (UnsatisfiedLinkError e) {
            log.error("Could not load the libDotMac library:" + e.getMessage());
        }
    }

    private native void downloadBookmarksFromDotMacActionNative(String destination);

    private native void uploadBookmarksToDotMacActionNative();

    public void downloadBookmarksFromDotMacAction(Object sender) {
        File tmp = new File(NSPathUtilities.temporaryDirectory(),
                "Favorites.plist");
        this.downloadBookmarksFromDotMacActionNative(tmp.getAbsolutePath());
        this.loadBookmarks(tmp);
        NSAlertPanel.runInformationalAlert(NSBundle.localizedString("Bookmarks Imported", ""),
                NSBundle.localizedString("Imported", "") + " " + this.noAdded + " " + NSBundle.localizedString("of", "") + " "
                + (this.noSkipped + this.noAdded) + " " + NSBundle.localizedString("bookmarks.", ""),
                NSBundle.localizedString("OK", ""), //default
                null,
                null);
        tmp.delete();
    }

    public void uploadBookmarksToDotMacAction(Object sender) {
        this.uploadBookmarksToDotMacActionNative();
    }

    private int noSkipped = 0;
    private int noAdded = 0;

    private boolean canceled = false;

    private void loadBookmarks(java.io.File f) {
        if (f.exists()) {
            log.info("Found Bookmarks file: " + f.toString());
            NSData plistData = new NSData(f);
            String[] errorString = new String[]{null};
            Object propertyListFromXMLData =
                    NSPropertyListSerialization.propertyListFromData(plistData,
                            NSPropertyListSerialization.PropertyListImmutable,
                            new int[]{NSPropertyListSerialization.PropertyListXMLFormat},
                            errorString);
            if (errorString[0] != null) {
                log.error("Problem reading bookmark file: " + errorString[0]);
            }
            else {
                log.debug("Successfully read Bookmarks: " + propertyListFromXMLData);
            }
            if (propertyListFromXMLData instanceof NSArray) {
                NSArray entries = (NSArray) propertyListFromXMLData;
                java.util.Enumeration i = entries.objectEnumerator();
                Object element;
                while (i.hasMoreElements()) {
                    element = i.nextElement();
                    if (element instanceof NSDictionary) {
                        this.addBookmark(new Host((NSDictionary) element));
                    }
                }
            }
        }
    }

    private boolean addBookmark(Object host) {
        if (canceled) {
            noSkipped++;
            return false;
        }
        if (host instanceof Host) {
            if (!CDBookmarkTableDataSource.instance().contains(host)) {
                int choice = NSAlertPanel.runAlert(NSBundle.localizedString("Import Bookmark", ""),
                        NSBundle.localizedString("Add the bookmark", "") + " (" + ((Host) host).getNickname() + ") "
                        + NSBundle.localizedString("to your list of bookmarks?", ""),
                        NSBundle.localizedString("Add", ""), //default
                        NSBundle.localizedString("Cancel", ""), //alternate
                        NSBundle.localizedString("Skip", "")); //other
                if (choice == NSAlertPanel.AlternateReturn) {
                    canceled = true;
                }
                if (choice == NSAlertPanel.OtherReturn) {
                    noSkipped++;
                    return false;
                }
                if (choice == NSAlertPanel.DefaultReturn) {
                    CDBookmarkTableDataSource.instance().add(host);
                    NSArray windows = NSApplication.sharedApplication().windows();
                    int count = windows.count();
                    while (0 != count--) {
                        NSWindow window = (NSWindow) windows.objectAtIndex(count);
                        CDBrowserController controller = CDBrowserController.controllerForWindow(window);
                        if (null != controller) {
                            controller.reloadBookmarks();
                        }
                    }
                    noAdded++;
                    return true;
                }
            }
            else {
                noSkipped++;
                return false;
            }
        }
        return false;
    }
}
