package ch.cyberduck.ui.cocoa;

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

import ch.cyberduck.core.*;

import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.*;

import org.apache.log4j.Logger;

import java.io.File;
import java.util.Iterator;
import java.util.List;

/**
 * @version $Id$
 */
public class CDConnectionController extends CDWindowController {
    private static Logger log = Logger.getLogger(CDConnectionController.class);

    // ----------------------------------------------------------
    // Outlets
    // ----------------------------------------------------------

    private NSPopUpButton bookmarksPopup;

    public void setBookmarksPopup(NSPopUpButton bookmarksPopup) {
        this.bookmarksPopup = bookmarksPopup;
        this.bookmarksPopup.setImage(NSImage.imageNamed("bookmarks.tiff"));
        this.bookmarksPopup.setToolTip(NSBundle.localizedString("Bookmarks", ""));
        Iterator iter = CDBookmarkTableDataSource.instance().iterator();
        while (iter.hasNext()) {
            bookmarksPopup.addItem(iter.next().toString());
        }
        this.bookmarksPopup.setTarget(this);
        this.bookmarksPopup.setAction(new NSSelector("bookmarksPopupSelectionChanged", new Class[]{Object.class}));
    }

    public void bookmarksPopupSelectionChanged(Object sender) {
        int index = CDBookmarkTableDataSource.instance().indexOf(bookmarksPopup.titleOfSelectedItem());
        this.bookmarkSelectionDidChange((Host) CDBookmarkTableDataSource.instance().get(index));
    }

    private NSPopUpButton historyPopup;
    private List history;

    private static final File HISTORY_FOLDER = new File(NSPathUtilities.stringByExpandingTildeInPath("~/Library/Application Support/Cyberduck/History"));

    public void setHistoryPopup(NSPopUpButton historyPopup) {
        this.historyPopup = historyPopup;
        this.historyPopup.setImage(NSImage.imageNamed("history.tiff"));
        this.historyPopup.setToolTip(NSBundle.localizedString("History", ""));
        File[] files = HISTORY_FOLDER.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".duck");
            }
        });
        this.history = new Collection();
        for (int i = 0; i < files.length; i++) {
            Host h = CDBookmarkTableDataSource.instance().importBookmark(files[i]);
            history.add(h);
            historyPopup.addItem(h.toString());
        }
        this.historyPopup.setTarget(this);
        this.historyPopup.setAction(new NSSelector("historyPopupSelectionChanged", new Class[]{Object.class}));
    }

    public void historyPopupSelectionChanged(Object sender) {
        int index = history.indexOf(historyPopup.titleOfSelectedItem());
        this.bookmarkSelectionDidChange((Host) history.get(index));
    }

    private NSPopUpButton rendezvousPopup;

    private void addItemToRendezvousPopup(String item) {
        this.rendezvousPopup.addItem(item);
    }

    private void removeItemFromRendezvousPopup(String item) {
        this.rendezvousPopup.removeItemWithTitle(item);
    }

    private RendezvousListener rendezvousListener;

    public void setRendezvousPopup(NSPopUpButton rendezvousPopup) {
        this.rendezvousPopup = rendezvousPopup;
        this.rendezvousPopup.setImage(NSImage.imageNamed("rendezvous16.tiff"));
        this.rendezvousPopup.setToolTip("Bonjour");
        this.rendezvousPopup.setTarget(this);
        this.rendezvousPopup.setAction(new NSSelector("rendezvousSelectionDidChange", new Class[]{Object.class}));
        Rendezvous.instance().addListener(rendezvousListener = new RendezvousListener() {
            public void serviceResolved(final String servicename) {
                invoke(new Runnable() {
                    public void run() {
                        addItemToRendezvousPopup(Rendezvous.instance().getDisplayedName(servicename));
                    }
                });
            }

            public void serviceLost(final String servicename) {
                invoke(new Runnable() {
                    public void run() {
                        removeItemFromRendezvousPopup(Rendezvous.instance().getDisplayedName(servicename));
                    }
                });
            }
        });
        for(Iterator iter = Rendezvous.instance().getServices().iterator(); iter.hasNext(); ) {
            this.addItemToRendezvousPopup(Rendezvous.instance().getDisplayedName((String)iter.next()));
        }
    }

    public void rendezvousSelectionDidChange(Object sender) {
        this.bookmarkSelectionDidChange((Host) Rendezvous.instance().getServiceWithDisplayedName(
                rendezvousPopup.titleOfSelectedItem()));
    }

    private NSPopUpButton protocolPopup;

    public void setProtocolPopup(NSPopUpButton protocolPopup) {
        this.protocolPopup = protocolPopup;
        this.protocolPopup.setEnabled(true);
        this.protocolPopup.removeAllItems();
        this.protocolPopup.addItemsWithTitles(new NSArray(new String[]{Session.FTP_STRING, Session.FTP_TLS_STRING, Session.SFTP_STRING}));
        this.protocolPopup.itemWithTitle(Session.FTP_STRING).setKeyEquivalentModifierMask(NSEvent.CommandKeyMask);
        this.protocolPopup.itemWithTitle(Session.FTP_STRING).setKeyEquivalent("f");
        this.protocolPopup.itemWithTitle(Session.SFTP_STRING).setKeyEquivalentModifierMask(NSEvent.CommandKeyMask);
        this.protocolPopup.itemWithTitle(Session.SFTP_STRING).setKeyEquivalent("s");
        this.protocolPopup.setTarget(this);
        this.protocolPopup.setAction(new NSSelector("protocolSelectionDidChange", new Class[]{Object.class}));
    }

    public void protocolSelectionDidChange(Object sender) {
        log.debug("protocolSelectionDidChange:" + sender);
        if (protocolPopup.selectedItem().title().equals(Session.FTP_STRING)) {
            this.portField.setIntValue(Session.FTP_PORT);
        }
        if (protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING)) {
            this.portField.setIntValue(Session.FTP_PORT);
        }
        if (protocolPopup.selectedItem().title().equals(Session.SFTP_STRING)) {
            this.portField.setIntValue(Session.SSH_PORT);
        }
        this.connectmodePopup.setEnabled(protocolPopup.selectedItem().title().equals(Session.FTP_STRING)
                || protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING));
        this.pkCheckbox.setEnabled(protocolPopup.selectedItem().title().equals(Session.SFTP_STRING));
        this.updateURLLabel(sender);
    }

    private NSComboBox hostPopup;
    private Object hostPopupDataSource;

    public void setHostPopup(NSComboBox hostPopup) {
        this.hostPopup = hostPopup;
        this.hostPopup.setTarget(this);
        this.hostPopup.setAction(new NSSelector("hostSelectionDidChange", new Class[]{Object.class}));
        this.hostPopup.setUsesDataSource(true);
        this.hostPopup.setDataSource(this.hostPopupDataSource = new Object() {
            public int numberOfItemsInComboBox(NSComboBox combo) {
                return CDBookmarkTableDataSource.instance().size();
            }

            public Object comboBoxObjectValueForItemAtIndex(NSComboBox combo, int row) {
                if (row < this.numberOfItemsInComboBox(combo)) {
                    return ((Host) CDBookmarkTableDataSource.instance().get(row)).getHostname();
                }
                return null;
            }
        });
    }

    public void hostSelectionDidChange(Object sender) {
        log.debug("hostSelectionDidChange:" + sender);
        this.updateURLLabel(sender);
    }

    public void hostFieldTextDidChange(Object sender) {
        try {
            Host h = Host.parse(hostPopup.stringValue());
            this.hostPopup.setStringValue(h.getHostname());
            if (h.getProtocol().equals(Session.FTP))
                this.protocolPopup.selectItemWithTitle(Session.FTP_STRING);
            if (h.getProtocol().equals(Session.FTP_TLS))
                this.protocolPopup.selectItemWithTitle(Session.FTP_TLS_STRING);
            if (h.getProtocol().equals(Session.SFTP))
                this.protocolPopup.selectItemWithTitle(Session.SFTP_STRING);
            this.portField.setStringValue(String.valueOf(h.getPort()));
            this.usernameField.setStringValue(h.getCredentials().getUsername());
            this.pathField.setStringValue(h.getDefaultPath());
        }
        catch (java.net.MalformedURLException e) {
            // ignore; just a hostname has been entered
        }
    }

    private NSTextField pathField;

    public void setPathField(NSTextField pathField) {
        this.pathField = pathField;
    }

    private NSTextField portField;

    public void setPortField(NSTextField portField) {
        this.portField = portField;
    }

    public void portFieldTextDidChange(Object sender) {
        if (null == this.portField.stringValue() || this.portField.stringValue().equals("")) {
            if (protocolPopup.selectedItem().title().equals(Session.SFTP_STRING)) {
                this.portField.setStringValue("" + Session.SSH_PORT);
            }
            if (protocolPopup.selectedItem().title().equals(Session.FTP_STRING)) {
                this.portField.setStringValue("" + Session.FTP_PORT);
            }
            if (protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING)) {
                this.portField.setStringValue("" + Session.FTP_PORT);
            }
        }
    }

    private NSTextField usernameField;

    public void setUsernameField(NSTextField usernameField) {
        this.usernameField = usernameField;
    }

    private NSTextField passField;

    public void setPassField(NSTextField passField) {
        this.passField = passField;
    }

    private NSTextField pkLabel;

    public void setPkLabel(NSTextField pkLabel) {
        this.pkLabel = pkLabel;
        this.pkLabel.setStringValue(NSBundle.localizedString("No Private Key selected", ""));
    }

    private NSButton keychainCheckbox;

    public void setKeychainCheckbox(NSButton keychainCheckbox) {
        this.keychainCheckbox = keychainCheckbox;
        this.keychainCheckbox.setState(NSCell.OffState);
    }

    private NSButton anonymousCheckbox; //IBOutlet

    public void setAnonymousCheckbox(NSButton anonymousCheckbox) {
        this.anonymousCheckbox = anonymousCheckbox;
        this.anonymousCheckbox.setTarget(this);
        this.anonymousCheckbox.setAction(new NSSelector("anonymousCheckboxClicked", new Class[]{NSButton.class}));
        this.anonymousCheckbox.setState(NSCell.OffState);
    }

    public void anonymousCheckboxClicked(NSButton sender) {
        switch (sender.state()) {
            case NSCell.OnState:
                this.usernameField.setEnabled(false);
                this.usernameField.setStringValue(Preferences.instance().getProperty("ftp.anonymous.name"));
                this.passField.setEnabled(false);
                break;
            case NSCell.OffState:
                this.usernameField.setEnabled(true);
                this.usernameField.setStringValue(Preferences.instance().getProperty("connection.login.name"));
                this.passField.setEnabled(true);
                break;
        }
    }

    private NSButton pkCheckbox;

    public void setPkCheckbox(NSButton pkCheckbox) {
        this.pkCheckbox = pkCheckbox;
        this.pkCheckbox.setTarget(this);
        this.pkCheckbox.setAction(new NSSelector("pkCheckboxSelectionDidChange", new Class[]{Object.class}));
        this.pkCheckbox.setState(NSCell.OffState);
    }

    public void pkCheckboxSelectionDidChange(Object sender) {
        log.debug("pkCheckboxSelectionDidChange");
        if (this.pkLabel.stringValue().equals(NSBundle.localizedString("No Private Key selected", ""))) {
            NSOpenPanel panel = NSOpenPanel.openPanel();
            panel.setCanChooseDirectories(false);
            panel.setCanChooseFiles(true);
            panel.setAllowsMultipleSelection(false);
            panel.beginSheetForDirectory(System.getProperty("user.home") + "/.ssh",
                    null,
                    null,
                    this.window(),
                    this,
                    new NSSelector("pkSelectionPanelDidEnd", new Class[]{NSOpenPanel.class, int.class, Object.class}),
                    null);
        }
        else {
            this.passField.setEnabled(true);
            this.pkCheckbox.setState(NSCell.OffState);
            this.pkLabel.setStringValue(NSBundle.localizedString("No Private Key selected", ""));
        }
    }

    public void pkSelectionPanelDidEnd(NSOpenPanel window, int returnCode, Object contextInfo) {
        switch (returnCode) {
            case (NSAlertPanel.DefaultReturn): {
                NSArray selected = window.filenames();
                java.util.Enumeration enumerator = selected.objectEnumerator();
                while (enumerator.hasMoreElements()) {
                    this.pkLabel.setStringValue((String) enumerator.nextElement());
                }
                this.passField.setEnabled(false);
                break;
            }
            case (NSAlertPanel.AlternateReturn): {
                this.passField.setEnabled(true);
                this.pkCheckbox.setState(NSCell.OffState);
                this.pkLabel.setStringValue(NSBundle.localizedString("No Private Key selected", ""));
                break;
            }
        }
    }

    private NSTextField urlLabel;

    public void setUrlLabel(NSTextField urlLabel) {
        this.urlLabel = urlLabel;
    }

    private NSPopUpButton encodingPopup; // IBOutlet

    public void setEncodingPopup(NSPopUpButton encodingPopup) {
        this.encodingPopup = encodingPopup;
        this.encodingPopup.setEnabled(true);
        this.encodingPopup.removeAllItems();
        java.util.SortedMap charsets = java.nio.charset.Charset.availableCharsets();
        String[] items = new String[charsets.size()];
        java.util.Iterator iterator = charsets.values().iterator();
        int i = 0;
        while (iterator.hasNext()) {
            items[i] = ((java.nio.charset.Charset) iterator.next()).name();
            i++;
        }
        this.encodingPopup.addItemsWithTitles(new NSArray(items));
        this.encodingPopup.setTitle(Preferences.instance().getProperty("browser.charset.encoding"));
    }

    private NSPopUpButton connectmodePopup; //IBOutlet

    private static final String CONNECTMODE_ACTIVE = NSBundle.localizedString("Active", "");
    private static final String CONNECTMODE_PASSIVE = NSBundle.localizedString("Passive", "");

    public void setConnectmodePopup(NSPopUpButton connectmodePopup) {
        this.connectmodePopup = connectmodePopup;
        this.connectmodePopup.removeAllItems();
        this.connectmodePopup.addItemsWithTitles(new NSArray(new String[]{CONNECTMODE_ACTIVE, CONNECTMODE_PASSIVE}));
        this.connectmodePopup.itemWithTitle(CONNECTMODE_PASSIVE).setKeyEquivalentModifierMask(NSEvent.CommandKeyMask);
        this.connectmodePopup.itemWithTitle(CONNECTMODE_PASSIVE).setKeyEquivalent("p");
        this.connectmodePopup.itemWithTitle(CONNECTMODE_ACTIVE).setKeyEquivalentModifierMask(NSEvent.CommandKeyMask);
        this.connectmodePopup.itemWithTitle(CONNECTMODE_ACTIVE).setKeyEquivalent("a");
        if (Preferences.instance().getProperty("ftp.connectmode").equals("active"))
            this.connectmodePopup.setTitle(CONNECTMODE_ACTIVE);
        if (Preferences.instance().getProperty("ftp.connectmode").equals("passive"))
            this.connectmodePopup.setTitle(CONNECTMODE_PASSIVE);
    }

    private CDBrowserController browserController;

    // ----------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------

    public CDConnectionController(CDBrowserController browserController) {
        this.browserController = browserController;
        if (!NSApplication.loadNibNamed("Connection", this)) {
            log.fatal("Couldn't load Connection.nib");
        }
    }

    public void awakeFromNib() {
        super.awakeFromNib();

        // Notify the updateURLLabel() method if the user types.
        //ControlTextDidChangeNotification
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("updateURLLabel", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.hostPopup);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("hostFieldTextDidChange", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.hostPopup);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("updateURLLabel", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.pathField);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("updateURLLabel", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.portField);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("portFieldTextDidChange", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.portField);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("updateURLLabel", new Class[]{Object.class}),
                NSControl.ControlTextDidChangeNotification,
                this.usernameField);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("getPasswordFromKeychain", new Class[]{Object.class}),
                NSControl.ControlTextDidEndEditingNotification,
                this.hostPopup);
        NSNotificationCenter.defaultCenter().addObserver(this,
                new NSSelector("getPasswordFromKeychain", new Class[]{Object.class}),
                NSControl.ControlTextDidEndEditingNotification,
                this.usernameField);

        this.usernameField.setStringValue(Preferences.instance().getProperty("connection.login.name"));
        if (Preferences.instance().getProperty("connection.protocol.default").equals(Session.FTP)) {
            this.protocolPopup.setTitle(Session.FTP_STRING);
            this.portField.setIntValue(Session.FTP_PORT);
        }
        if (Preferences.instance().getProperty("connection.protocol.default").equals(Session.FTP_TLS)) {
            this.protocolPopup.setTitle(Session.FTP_TLS_STRING);
            this.portField.setIntValue(Session.FTP_PORT);
        }
        if (Preferences.instance().getProperty("connection.protocol.default").equals(Session.SFTP)) {
            this.protocolPopup.setTitle(Session.SFTP_STRING);
            this.portField.setIntValue(Session.SSH_PORT);
        }
        this.connectmodePopup.setEnabled(Preferences.instance().getProperty("connection.protocol.default").equals(Session.FTP));
        this.pkCheckbox.setEnabled(Preferences.instance().getProperty("connection.protocol.default").equals(Session.SFTP));
    }

    /**
     * Updating the password field with the actual password if any
     * is avaialble for this hostname
     */
    public void getPasswordFromKeychain(Object sender) {
        if (Preferences.instance().getBoolean("connection.login.useKeychain")) {
            if (hostPopup.stringValue() != null && !hostPopup.stringValue().equals("") &&
                    usernameField.stringValue() != null && !usernameField.stringValue().equals("")) {
                String protocol = Preferences.instance().getProperty("connection.protocol.default");
                if (protocolPopup.selectedItem().title().equals(Session.SFTP_STRING)) {
                    protocol = Session.SFTP;
                }
                else if (protocolPopup.selectedItem().title().equals(Session.FTP_STRING)) {
                    protocol = Session.FTP;
                }
                else if (protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING)) {
                    protocol = Session.FTP_TLS;
                }
                Login l = new Login(hostPopup.stringValue(), protocol, usernameField.stringValue(), null);
                String passFromKeychain = l.getInternetPasswordFromKeychain();
                if (null == passFromKeychain || passFromKeychain.equals("")) {
                    passFromKeychain = l.getPasswordFromKeychain(); //legacy support
                }
                if (passFromKeychain != null && !passFromKeychain.equals("")) {
                    log.info("Password for " + usernameField.stringValue() + " found in Keychain");
                    this.passField.setStringValue(passFromKeychain);
                }
                else {
                    log.info("Password for " + usernameField.stringValue() + " *not* found in Keychain");
                    this.passField.setStringValue("");
                }
            }
        }
    }

    private void bookmarkSelectionDidChange(Host selectedItem) {
        if (selectedItem.getProtocol().equals(Session.FTP)) {
            this.protocolPopup.selectItemWithTitle(Session.FTP_STRING);
        }
        if (selectedItem.getProtocol().equals(Session.FTP_TLS)) {
            this.protocolPopup.selectItemWithTitle(Session.FTP_TLS_STRING);
        }
        if (selectedItem.getProtocol().equals(Session.SFTP)) {
            this.protocolPopup.selectItemWithTitle(Session.SFTP_STRING);
        }
        this.hostPopup.setStringValue(selectedItem.getHostname());
        this.portField.setIntValue(selectedItem.getPort());
        this.pathField.setStringValue(selectedItem.getDefaultPath());
        this.usernameField.setStringValue(selectedItem.getCredentials().getUsername());
        this.connectmodePopup.setEnabled(selectedItem.getProtocol().equals(Session.FTP));
        this.pkCheckbox.setEnabled(selectedItem.getProtocol().equals(Session.SFTP));
        if (selectedItem.getCredentials().getPrivateKeyFile() != null) {
            this.pkCheckbox.setState(NSCell.OnState);
            this.pkLabel.setStringValue(selectedItem.getCredentials().getPrivateKeyFile());
        }
        else {
            this.pkCheckbox.setState(NSCell.OffState);
        }
        this.encodingPopup.setTitle(selectedItem.getEncoding());
        this.updateURLLabel(null);
    }

    private void updateURLLabel(Object sender) {
        String protocol = null;
        if (protocolPopup.selectedItem().title().equals(Session.SFTP_STRING)) {
            protocol = Session.SFTP + "://";
        }
        if (protocolPopup.selectedItem().title().equals(Session.FTP_STRING)) {
            protocol = Session.FTP + "://";
        }
        if (protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING)) {
            protocol = Session.FTP_TLS + "://";
        }
        urlLabel.setStringValue(protocol + usernameField.stringValue()
                + "@" + hostPopup.stringValue() + ":" + portField.stringValue()
                + Path.DELIMITER + pathField.stringValue());
    }

    public void closeSheet(NSButton sender) {
        this.endSheet(this.window(), sender.tag());
    }

    public void sheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
        sheet.orderOut(null);
        switch (returncode) {
            case (NSAlertPanel.DefaultReturn):
                Host host = null;
                if (protocolPopup.selectedItem().title().equals(Session.SFTP_STRING)) {
                    // SFTP has been selected as the protocol to connect with
                    host = new Host(Session.SFTP,
                            hostPopup.stringValue(),
                            Integer.parseInt(portField.stringValue()),
                            pathField.stringValue());
                    host.setCredentials(usernameField.stringValue(), passField.stringValue(), keychainCheckbox.state() == NSCell.OnState);
                    if (pkCheckbox.state() == NSCell.OnState) {
                        host.getCredentials().setPrivateKeyFile(pkLabel.stringValue());
                    }
                }
                else if (protocolPopup.selectedItem().title().equals(Session.FTP_STRING)) {
                    // FTP has been selected as the protocol to connect with
                    host = new Host(Session.FTP,
                            hostPopup.stringValue(),
                            Integer.parseInt(portField.stringValue()),
                            pathField.stringValue());
                    host.setCredentials(usernameField.stringValue(), passField.stringValue(), keychainCheckbox.state() == NSCell.OnState);
                    if (connectmodePopup.selectedItem().title().equals(CONNECTMODE_ACTIVE)) {
                        host.setFTPConnectMode(com.enterprisedt.net.ftp.FTPConnectMode.ACTIVE);
                    }
                    if (connectmodePopup.selectedItem().title().equals(CONNECTMODE_PASSIVE)) {
                        host.setFTPConnectMode(com.enterprisedt.net.ftp.FTPConnectMode.PASV);
                    }
                }
                else if (protocolPopup.selectedItem().title().equals(Session.FTP_TLS_STRING)) {
                    // FTP has been selected as the protocol to connect with
                    host = new Host(Session.FTP_TLS,
                            hostPopup.stringValue(),
                            Integer.parseInt(portField.stringValue()),
                            pathField.stringValue());
                    host.setCredentials(usernameField.stringValue(), passField.stringValue(), keychainCheckbox.state() == NSCell.OnState);
                    if (connectmodePopup.selectedItem().title().equals(CONNECTMODE_ACTIVE)) {
                        host.setFTPConnectMode(com.enterprisedt.net.ftp.FTPConnectMode.ACTIVE);
                    }
                    if (connectmodePopup.selectedItem().title().equals(CONNECTMODE_PASSIVE)) {
                        host.setFTPConnectMode(com.enterprisedt.net.ftp.FTPConnectMode.PASV);
                    }
                }
                else {
                    throw new IllegalArgumentException("No protocol selected.");
                }
                browserController.setEncoding(encodingPopup.titleOfSelectedItem());
                browserController.mount(host);
        }
        Rendezvous.instance().removeListener(this.rendezvousListener);
        NSNotificationCenter.defaultCenter().removeObserver(this);
        instances.removeObject(this);
    }
}
