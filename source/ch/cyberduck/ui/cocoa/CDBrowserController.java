package ch.cyberduck.ui.cocoa;

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

import java.io.File;
import java.util.*;

import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.*;

import org.apache.log4j.Logger;

import ch.cyberduck.core.*;
import ch.cyberduck.ui.cocoa.odb.Editor;

/**
 * @version $Id$
 */
public class CDBrowserController extends CDController implements Observer {
	private static Logger log = Logger.getLogger(CDBrowserController.class);

	private static final File HISTORY_FOLDER = new File(NSPathUtilities.stringByExpandingTildeInPath("~/Library/Application Support/Cyberduck/History"));

	static {
		HISTORY_FOLDER.mkdirs();
	}

	/**
	 * Keep references of controller objects because otherweise they get garbage collected
	 * if not referenced here.
	 */
	private static NSMutableArray instances = new NSMutableArray();
	
	// ----------------------------------------------------------
	// Constructor
	// ----------------------------------------------------------
	
	public CDBrowserController() {
		instances.addObject(this);
		if(false == NSApplication.loadNibNamed("Browser", this)) {
			log.fatal("Couldn't load Browser.nib");
		}
	}

	public static CDBrowserController controllerForWindow(NSWindow window) {
		if(window.isVisible()) {
			Object delegate = window.delegate();
			if(delegate != null && delegate instanceof CDBrowserController) {
				return (CDBrowserController)delegate;
			}
		}
		return null;
	}

	public void awakeFromNib() {
		log.debug("awakeFromNib");
		this.window().setTitle("Cyberduck "+NSBundle.bundleForClass(this.getClass()).objectForInfoDictionaryKey("CFBundleVersion"));
		this.window().setInitialFirstResponder(quickConnectPopup);
		// Drawer states
		if(Preferences.instance().getProperty("logDrawer.isOpen").equals("true")) {
			this.logDrawer.open();
		}
		if(Preferences.instance().getProperty("bookmarkDrawer.isOpen").equals("true")) {
			this.bookmarkDrawer.open();
		}
		// Toolbar
		this.toolbar = new NSToolbar("Cyberduck Toolbar");
		this.toolbar.setDelegate(this);
		this.toolbar.setAllowsUserCustomization(true);
		this.toolbar.setAutosavesConfiguration(true);
		this.window().setToolbar(toolbar);
	}

	public void update(final Observable o, final Object arg) {
		log.debug("update:"+o+","+arg);
		if(arg instanceof Path) {
			this.workdir = (Path)arg;
			this.pathPopupItems.clear();
			this.pathPopupButton.removeAllItems();
			this.addPathToPopup(this.workdir);
			for(Path p = this.workdir; !p.isRoot();) {
				p = p.getParent();
				this.addPathToPopup(p);
			}
			this.browserModel.setData(this.workdir.getSession().cache().get(this.workdir.getAbsolute()));
			NSTableColumn selectedColumn = this.browserModel.selectedColumn() != null ? this.browserModel.selectedColumn() : browserTable.tableColumnWithIdentifier("FILENAME");
			this.browserTable.setIndicatorImage(this.browserModel.isSortedAscending() ? NSImage.imageNamed("NSAscendingSortIndicator") : NSImage.imageNamed("NSDescendingSortIndicator"), selectedColumn);
			this.browserModel.sort(selectedColumn, this.browserModel.isSortedAscending());
			this.browserTable.reloadData();
			this.window().makeFirstResponder(browserTable);
			this.infoLabel.setStringValue(this.browserModel.numberOfRowsInTableView(this.browserTable)+" "+
			    NSBundle.localizedString("files", ""));
			this.toolbar.validateVisibleItems();
		}
		else if(arg instanceof Message) {
			Message msg = (Message)arg;
			if(msg.getTitle().equals(Message.ERROR)) {
				this.progressIndicator.stopAnimation(this);
				this.statusIcon.setImage(NSImage.imageNamed("alert.tiff"));
				this.statusIcon.setNeedsDisplay(true);
				this.statusLabel.setObjectValue(msg.getContent());
				this.statusLabel.display();
				this.beginSheet(NSAlertPanel.criticalAlertPanel(NSBundle.localizedString("Error", "Alert sheet title"), //title
				    (String)msg.getContent(), // message
				    NSBundle.localizedString("OK", "Alert default button"), // defaultbutton
				    null, //alternative button
				    null //other button
				),
				    true);
				//this.window().setDocumentEdited(false);
			}
			else if(msg.getTitle().equals(Message.REFRESH)) {
				refreshButtonClicked(null);
			}
			else if(msg.getTitle().equals(Message.PROGRESS)) {
				this.statusLabel.setObjectValue(msg.getContent());
				this.statusLabel.display();
			}
			else if(msg.getTitle().equals(Message.OPEN)) {
				this.progressIndicator.startAnimation(this);
				this.statusIcon.setImage(null);
				this.statusIcon.setNeedsDisplay(true);
				this.browserModel.clear();
				this.browserTable.reloadData();
				this.pathPopupItems.clear();
				this.pathPopupButton.removeAllItems();
				this.toolbar.validateVisibleItems();
				//this.window().setDocumentEdited(true);
			}
			else if(msg.getTitle().equals(Message.CLOSE)) {
				this.progressIndicator.stopAnimation(this);
				this.statusIcon.setImage(null);
				this.statusIcon.setNeedsDisplay(true);
				this.toolbar.validateVisibleItems();
				//this.window().setDocumentEdited(false);
			}
			else if(msg.getTitle().equals(Message.START)) {
				this.statusIcon.setImage(null);
				this.statusIcon.setNeedsDisplay(true);
				this.progressIndicator.startAnimation(this);
				this.toolbar.validateVisibleItems();
			}
			else if(msg.getTitle().equals(Message.STOP)) {
				this.progressIndicator.stopAnimation(this);
				this.statusLabel.setObjectValue(NSBundle.localizedString("Idle", "No background thread is running"));
				this.statusLabel.display();
				this.toolbar.validateVisibleItems();
			}
		}
	}

	public void alertSheetDidClose(NSWindow window, int returncode, Object context) {
		this.notify();
	}

	// ----------------------------------------------------------
	// Outlets
	// ----------------------------------------------------------

	private NSToolbar toolbar;

	private NSTextView logView;

	public void setLogView(NSTextView logView) {
		this.logView = logView;
		this.logView.setFont(NSFont.userFixedPitchFontOfSize(9.0f));
	}

	private CDBrowserTableDataSource browserModel;
	private NSTableView browserTable; // IBOutlet

	public void setBrowserTable(NSTableView browserTable) {
		log.debug("setBrowserTable");
		this.browserTable = browserTable;
		this.browserTable.setTarget(this);
		this.browserTable.setDoubleAction(new NSSelector("browserTableRowDoubleClicked", new Class[]{Object.class}));
		this.browserTable.setDataSource(this.browserModel = new CDBrowserTableDataSource());
		this.browserTable.setDelegate(this.browserModel);
		// receive drag events from types
		this.browserTable.registerForDraggedTypes(new NSArray(new Object[]{
			"QueuePboardType",
			NSPasteboard.FilenamesPboardType, //accept files dragged from the Finder for uploading
			NSPasteboard.FilesPromisePboardType} //accept file promises made myself but then interpret them as QueuePboardType
		));

		// setting appearance attributes
		this.browserTable.setRowHeight(17f);
		this.browserTable.setAutoresizesAllColumnsToFit(true);
		NSSelector setUsesAlternatingRowBackgroundColorsSelector =
		    new NSSelector("setUsesAlternatingRowBackgroundColors", new Class[]{boolean.class});
		if(setUsesAlternatingRowBackgroundColorsSelector.implementedByClass(NSTableView.class)) {
			this.browserTable.setUsesAlternatingRowBackgroundColors(Preferences.instance().getProperty("browser.alternatingRows").equals("true"));
		}
		NSSelector setGridStyleMaskSelector =
		    new NSSelector("setGridStyleMask", new Class[]{int.class});
		if(setGridStyleMaskSelector.implementedByClass(NSTableView.class)) {
			if(Preferences.instance().getProperty("browser.horizontalLines").equals("true") && Preferences.instance().getProperty("browser.verticalLines").equals("true")) {
				this.browserTable.setGridStyleMask(NSTableView.SolidHorizontalGridLineMask | NSTableView.SolidVerticalGridLineMask);
			}
			else if(Preferences.instance().getProperty("browser.verticalLines").equals("true")) {
				this.browserTable.setGridStyleMask(NSTableView.SolidVerticalGridLineMask);
			}
			else if(Preferences.instance().getProperty("browser.horizontalLines").equals("true")) {
				this.browserTable.setGridStyleMask(NSTableView.SolidHorizontalGridLineMask);
			}
			else {
				this.browserTable.setGridStyleMask(NSTableView.GridNone);
			}
		}
		// ading table columns
		if(Preferences.instance().getProperty("browser.columnIcon").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.setIdentifier("TYPE");
			c.headerCell().setStringValue("");
			c.setMinWidth(20f);
			c.setWidth(20f);
			c.setMaxWidth(20f);
			c.setResizable(true);
			c.setEditable(false);
			c.setDataCell(new NSImageCell());
			c.dataCell().setAlignment(NSText.CenterTextAlignment);
			this.browserTable.addTableColumn(c);
		}
		if(Preferences.instance().getProperty("browser.columnFilename").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.headerCell().setStringValue(NSBundle.localizedString("Filename", "A column in the browser"));
			c.setIdentifier("FILENAME");
			c.setMinWidth(100f);
			c.setWidth(250f);
			c.setMaxWidth(1000f);
			c.setResizable(true);
			c.setEditable(false);
			c.setDataCell(new NSTextFieldCell());
			c.dataCell().setAlignment(NSText.LeftTextAlignment);
			this.browserTable.addTableColumn(c);
		}
		if(Preferences.instance().getProperty("browser.columnSize").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.headerCell().setStringValue(NSBundle.localizedString("Size", "A column in the browser"));
			c.setIdentifier("SIZE");
			c.setMinWidth(50f);
			c.setWidth(80f);
			c.setMaxWidth(100f);
			c.setResizable(true);
			c.setDataCell(new NSTextFieldCell());
			c.dataCell().setAlignment(NSText.RightTextAlignment);
			this.browserTable.addTableColumn(c);
		}
		if(Preferences.instance().getProperty("browser.columnModification").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.headerCell().setStringValue(NSBundle.localizedString("Modified", "A column in the browser"));
			c.setIdentifier("MODIFIED");
			c.setMinWidth(100f);
			c.setWidth(180f);
			c.setMaxWidth(500f);
			c.setResizable(true);
			c.setDataCell(new NSTextFieldCell());
			c.dataCell().setAlignment(NSText.LeftTextAlignment);
			c.dataCell().setFormatter(new NSGregorianDateFormatter((String)NSUserDefaults.standardUserDefaults().objectForKey(NSUserDefaults.ShortTimeDateFormatString),
			    true));
			this.browserTable.addTableColumn(c);
		}
		if(Preferences.instance().getProperty("browser.columnOwner").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.headerCell().setStringValue(NSBundle.localizedString("Owner", "A column in the browser"));
			c.setIdentifier("OWNER");
			c.setMinWidth(100f);
			c.setWidth(80f);
			c.setMaxWidth(500f);
			c.setResizable(true);
			c.setDataCell(new NSTextFieldCell());
			c.dataCell().setAlignment(NSText.LeftTextAlignment);
			this.browserTable.addTableColumn(c);
		}
		if(Preferences.instance().getProperty("browser.columnPermissions").equals("true")) {
			NSTableColumn c = new NSTableColumn();
			c.headerCell().setStringValue(NSBundle.localizedString("Permissions", "A column in the browser"));
			c.setIdentifier("PERMISSIONS");
			c.setMinWidth(100f);
			c.setWidth(100f);
			c.setMaxWidth(800f);
			c.setResizable(true);
			c.setDataCell(new NSTextFieldCell());
			c.dataCell().setAlignment(NSText.LeftTextAlignment);
			this.browserTable.addTableColumn(c);
		}

		this.browserTable.sizeToFit();

		// selection properties
		this.browserTable.setAllowsMultipleSelection(true);
		this.browserTable.setAllowsEmptySelection(true);
		this.browserTable.setAllowsColumnReordering(true);
	}

	public void browserTableRowDoubleClicked(Object sender) {
		log.debug("browserTableRowDoubleClicked");
		searchField.setStringValue("");
		if(this.browserModel.numberOfRowsInTableView(browserTable) > 0 && browserTable.numberOfSelectedRows() > 0) {
			Path p = (Path)this.browserModel.getEntry(browserTable.selectedRow()); //last row selected
			if(p.attributes.isFile() || browserTable.numberOfSelectedRows() > 1) {
				if(Preferences.instance().getProperty("browser.doubleClickOnFile").equals("edit")) {
					this.editButtonClicked(sender);
				}
				else {
					this.downloadButtonClicked(sender);
				}
			}
			if(p.attributes.isDirectory()) {
				p.list();
			}
		}
	}

	private CDBookmarkTableDataSource bookmarkModel;
	private NSTableView bookmarkTable; // IBOutlet

	public void setBookmarkTable(NSTableView bookmarkTable) {
		log.debug("setBookmarkTable");
		this.bookmarkTable = bookmarkTable;
		this.bookmarkTable.setDelegate(this);
		this.bookmarkTable.setTarget(this);
		this.bookmarkTable.setDoubleAction(new NSSelector("bookmarkTableRowDoubleClicked", new Class[]{Object.class}));
		this.bookmarkTable.setDataSource(this.bookmarkModel = CDBookmarkTableDataSource.instance());

		// receive drag events from types
		this.bookmarkTable.registerForDraggedTypes(new NSArray(new Object[]
		{NSPasteboard.FilenamesPboardType})); //accept bookmark files dragged from the Finder
		this.bookmarkTable.setRowHeight(45f);

		NSTableColumn iconColumn = new NSTableColumn();
		iconColumn.setIdentifier("ICON");
		iconColumn.setMinWidth(32f);
		iconColumn.setWidth(32f);
		iconColumn.setMaxWidth(32f);
		iconColumn.setEditable(false);
		iconColumn.setResizable(true);
		iconColumn.setDataCell(new NSImageCell());
		this.bookmarkTable.addTableColumn(iconColumn);

		NSTableColumn bookmarkColumn = new NSTableColumn();
		bookmarkColumn.setIdentifier("BOOKMARK");
		bookmarkColumn.setMinWidth(50f);
		bookmarkColumn.setWidth(200f);
		bookmarkColumn.setMaxWidth(500f);
		bookmarkColumn.setEditable(false);
		bookmarkColumn.setResizable(true);
		bookmarkColumn.setDataCell(new CDBookmarkCell());
		this.bookmarkTable.addTableColumn(bookmarkColumn);

		// setting appearance attributes
		this.bookmarkTable.setAutoresizesAllColumnsToFit(true);
		NSSelector setUsesAlternatingRowBackgroundColorsSelector =
		    new NSSelector("setUsesAlternatingRowBackgroundColors", new Class[]{boolean.class});
		if(setUsesAlternatingRowBackgroundColorsSelector.implementedByClass(NSTableView.class)) {
			this.bookmarkTable.setUsesAlternatingRowBackgroundColors(Preferences.instance().getProperty("browser.alternatingRows").equals("true"));
		}
		NSSelector setGridStyleMaskSelector =
		    new NSSelector("setGridStyleMask", new Class[]{int.class});
		if(setGridStyleMaskSelector.implementedByClass(NSTableView.class)) {
			this.bookmarkTable.setGridStyleMask(NSTableView.SolidHorizontalGridLineMask);
		}
		this.bookmarkTable.setAutoresizesAllColumnsToFit(true);

		// selection properties
		this.bookmarkTable.setAllowsMultipleSelection(false);
		this.bookmarkTable.setAllowsEmptySelection(true);
		this.bookmarkTable.setAllowsColumnReordering(false);

		this.bookmarkTable.sizeToFit();

		(NSNotificationCenter.defaultCenter()).addObserver(this,
		    new NSSelector("bookmarkSelectionDidChange", new Class[]{NSNotification.class}),
		    NSTableView.TableViewSelectionDidChangeNotification,
		    this.bookmarkTable);
	}

	public void bookmarkSelectionDidChange(NSNotification notification) {
		log.debug("bookmarkSelectionDidChange");
		editBookmarkButton.setEnabled(bookmarkTable.numberOfSelectedRows() == 1);
		removeBookmarkButton.setEnabled(bookmarkTable.numberOfSelectedRows() == 1);
	}

	public void bookmarkTableRowDoubleClicked(Object sender) {
		log.debug("bookmarkTableRowDoubleClicked");
		if(this.bookmarkTable.selectedRow() != -1) {
			Host host = (Host)bookmarkModel.getItem(bookmarkTable.selectedRow());
			this.window().setTitle(host.getProtocol()+":"+host.getHostname());
			this.mount(host);
		}
	}

	private NSComboBox quickConnectPopup; // IBOutlet
	private Object quickConnectDataSource;

	public void setQuickConnectPopup(NSComboBox quickConnectPopup) {
		this.quickConnectPopup = quickConnectPopup;
		this.quickConnectPopup.setTarget(this);
		this.quickConnectPopup.setAction(new NSSelector("quickConnectSelectionChanged", new Class[]{Object.class}));
		this.quickConnectPopup.setUsesDataSource(true);
		this.quickConnectPopup.setDataSource(this.quickConnectDataSource = new Object() {
			public int numberOfItemsInComboBox(NSComboBox combo) {
				return CDBookmarkTableDataSource.instance().size();
			}

			public Object comboBoxObjectValueForItemAtIndex(NSComboBox combo, int row) {
				return CDBookmarkTableDataSource.instance().getItem(row).getHostname();
			}
		});
	}

	public void quickConnectSelectionChanged(Object sender) {
		log.debug("quickConnectSelectionChanged");
		String input = ((NSControl)sender).stringValue();
		for(Iterator iter = bookmarkModel.iterator(); iter.hasNext();) {
			Host h = (Host)iter.next();
			if(h.getHostname().equals(input)) {
				this.mount(h);
				return;
			}
		}
		int index;
		Host host = null;
		if((index = input.indexOf('@')) != -1) {
			host = new Host(input.substring(index+1, input.length()),
			    new Login(input.substring(index+1, input.length()),
			        input.substring(0, index), null));
		}
		else {
			host = new Host(input, new Login(input, null, null));
			if(host.getProtocol().equals(Session.FTP)) {
				host.getLogin().setUsername(Preferences.instance().getProperty("ftp.anonymous.name"));
			}
			else {
				host.getLogin().setUsername(Preferences.instance().getProperty("connection.login.name"));
			}
		}
		this.mount(host);
	}

	private NSTextField searchField; // IBOutlet

	public void setSearchField(NSTextField searchField) {
		this.searchField = searchField;
		NSNotificationCenter.defaultCenter().addObserver(this,
		    new NSSelector("searchFieldTextDidChange", new Class[]{Object.class}),
		    NSControl.ControlTextDidChangeNotification,
		    searchField);
	}

	public void searchFieldTextDidChange(NSNotification notification) {
		String searchString = null;
		NSDictionary userInfo = notification.userInfo();
		if(null != userInfo) {
			Object o = userInfo.allValues().lastObject();
			if(null != o) {
				searchString = ((NSText)o).string();
				log.debug("searchFieldTextDidChange:"+searchString);
				Iterator i = browserModel.values().iterator();
				if(null == searchString || searchString.length() == 0) {
					this.browserModel.setActiveSet(this.browserModel.values());
					this.browserTable.reloadData();
				}
				else {
					List subset = new ArrayList();
					Path next;
					while(i.hasNext()) {
						next = (Path)i.next();
						if(next.getName().toLowerCase().indexOf(searchString.toLowerCase()) != -1) {
							subset.add(next);
						}
					}
					this.browserModel.setActiveSet(subset);
					this.browserTable.reloadData();
				}
			}
		}
	}

	// ----------------------------------------------------------
	// Manage Bookmarks
	// ----------------------------------------------------------

	private NSButton editBookmarkButton; // IBOutlet

	public void setEditBookmarkButton(NSButton editBookmarkButton) {
		this.editBookmarkButton = editBookmarkButton;
		this.editBookmarkButton.setImage(NSImage.imageNamed("edit.tiff"));
		this.editBookmarkButton.setAlternateImage(NSImage.imageNamed("editPressed.tiff"));
		this.editBookmarkButton.setTarget(this);
		this.editBookmarkButton.setEnabled(false);
		this.editBookmarkButton.setAction(new NSSelector("editBookmarkButtonClicked", new Class[]{Object.class}));
	}

	public void editBookmarkButtonClicked(Object sender) {
		this.bookmarkDrawer.open();
		CDBookmarkController controller = new CDBookmarkController(bookmarkTable,
		    bookmarkModel.getItem(bookmarkTable.selectedRow()));
		controller.window().makeKeyAndOrderFront(null);
	}

	private NSButton addBookmarkButton; // IBOutlet

	public void setAddBookmarkButton(NSButton addBookmarkButton) {
		this.addBookmarkButton = addBookmarkButton;
		this.addBookmarkButton.setImage(NSImage.imageNamed("add"));
		this.addBookmarkButton.setAlternateImage(NSImage.imageNamed("addPressed.tiff"));
		this.addBookmarkButton.setTarget(this);
		this.addBookmarkButton.setAction(new NSSelector("addBookmarkButtonClicked", new Class[]{Object.class}));
	}

	public void addBookmarkButtonClicked(Object sender) {
		this.bookmarkDrawer.open();
		Host item;
		if(this.isMounted()) {
			Host h = this.workdir().getSession().getHost();
			item = new Host(h.getProtocol(),
			    h.getHostname(),
			    h.getPort(),
			    new Login(h.getHostname(), h.getLogin().getUsername(), h.getLogin().getPassword()),
			    this.workdir().getAbsolute());
		}
		else {
			item = new Host("", new Login("", null, null));
		}
		bookmarkModel.addItem(item);
		this.bookmarkTable.reloadData();
		this.bookmarkTable.selectRow(bookmarkModel.indexOf(item), false);
		this.bookmarkTable.scrollRowToVisible(bookmarkModel.indexOf(item));
		CDBookmarkController controller = new CDBookmarkController(bookmarkTable, item);
	}

	private NSButton removeBookmarkButton; // IBOutlet

	public void setRemoveBookmarkButton(NSButton removeBookmarkButton) {
		this.removeBookmarkButton = removeBookmarkButton;
		this.removeBookmarkButton.setImage(NSImage.imageNamed("remove.tiff"));
		this.removeBookmarkButton.setAlternateImage(NSImage.imageNamed("removePressed.tiff"));
		this.removeBookmarkButton.setTarget(this);
		this.removeBookmarkButton.setEnabled(false);
		this.removeBookmarkButton.setAction(new NSSelector("removeBookmarkButtonClicked", new Class[]{Object.class}));
	}

	public void removeBookmarkButtonClicked(Object sender) {
		this.bookmarkDrawer.open();
		switch(NSAlertPanel.runCriticalAlert(NSBundle.localizedString("Delete Bookmark", ""),
		    NSBundle.localizedString("Do you want to delete the selected bookmark?", ""),
		    NSBundle.localizedString("Delete", ""),
		    NSBundle.localizedString("Cancel", ""),
		    null)) {
			case NSAlertPanel.DefaultReturn:
				bookmarkModel.removeItem(bookmarkTable.selectedRow());
				this.bookmarkTable.reloadData();
				break;
			case NSAlertPanel.AlternateReturn:
				break;
		}
	}

	// ----------------------------------------------------------
	// Browser navigation
	// ----------------------------------------------------------

	private NSButton upButton; // IBOutlet

	public void setUpButton(NSButton upButton) {
		this.upButton = upButton;
		this.upButton.setImage(NSImage.imageNamed("arrowUp16.tiff"));
		this.upButton.setTarget(this);
		this.upButton.setAction(new NSSelector("upButtonClicked", new Class[]{Object.class}));
	}

	private NSButton backButton; // IBOutlet

	public void setBackButton(NSButton backButton) {
		this.backButton = backButton;
		this.backButton.setImage(NSImage.imageNamed("arrowLeft16.tiff"));
		this.backButton.setTarget(this);
		this.backButton.setAction(new NSSelector("backButtonClicked", new Class[]{Object.class}));
	}

	private static final NSImage DISK_ICON = NSImage.imageNamed("disk.tiff");

	private List pathPopupItems = new ArrayList();
	private Path workdir;

	private NSPopUpButton pathPopupButton; // IBOutlet

	public void setPathPopup(NSPopUpButton pathPopupButton) {
		this.pathPopupButton = pathPopupButton;
		this.pathPopupButton.setTarget(this);
		this.pathPopupButton.setAction(new NSSelector("pathPopupSelectionChanged", new Class[]{Object.class}));
	}

	public void pathPopupSelectionChanged(Object sender) {
		Path p = (Path)pathPopupItems.get(pathPopupButton.indexOfSelectedItem());
		p.list();
	}

	private void addPathToPopup(Path p) {
		this.pathPopupItems.add(p);
		this.pathPopupButton.addItem(p.getAbsolute());
		if(p.isRoot()) {
			this.pathPopupButton.itemAtIndex(this.pathPopupButton.numberOfItems()-1).setImage(DISK_ICON);
		}
		else {
			this.pathPopupButton.itemAtIndex(this.pathPopupButton.numberOfItems()-1).setImage(FOLDER_ICON);
		}
	}

	// ----------------------------------------------------------
	// Drawers
	// ----------------------------------------------------------

	private NSDrawer logDrawer; // IBOutlet

	public void setLogDrawer(NSDrawer logDrawer) {
		this.logDrawer = logDrawer;
	}

	public void toggleLogDrawer(Object sender) {
		logDrawer.toggle(this);
		Preferences.instance().setProperty("logDrawer.isOpen", logDrawer.state() == NSDrawer.OpenState || logDrawer.state() == NSDrawer.OpeningState);
	}

	private NSDrawer bookmarkDrawer; // IBOutlet

	public void setBookmarkDrawer(NSDrawer bookmarkDrawer) {
		this.bookmarkDrawer = bookmarkDrawer;
		this.bookmarkDrawer.setDelegate(this);
	}

	public void toggleBookmarkDrawer(Object sender) {
		bookmarkDrawer.toggle(this);
		Preferences.instance().setProperty("bookmarkDrawer.isOpen", bookmarkDrawer.state() == NSDrawer.OpenState || bookmarkDrawer.state() == NSDrawer.OpeningState);
	}

	// ----------------------------------------------------------
	// Status
	// ----------------------------------------------------------

	private NSProgressIndicator progressIndicator; // IBOutlet

	public void setProgressIndicator(NSProgressIndicator progressIndicator) {
		this.progressIndicator = progressIndicator;
		this.progressIndicator.setIndeterminate(true);
		this.progressIndicator.setUsesThreadedAnimation(true);
	}

	private NSImageView statusIcon; // IBOutlet

	public void setStatusIcon(NSImageView statusIcon) {
		this.statusIcon = statusIcon;
	}

	private NSTextField statusLabel; // IBOutlet

	public void setStatusLabel(NSTextField statusLabel) {
		this.statusLabel = statusLabel;
		this.statusLabel.setObjectValue(NSBundle.localizedString("Idle", "No background thread is running"));
	}

	private NSTextField infoLabel; // IBOutlet

	public void setInfoLabel(NSTextField infoLabel) {
		this.infoLabel = infoLabel;
	}

	// ----------------------------------------------------------
	// Selector methods for the toolbar items
	// ----------------------------------------------------------

	public void editButtonClicked(Object sender) {
		NSEnumerator enum = browserTable.selectedRowEnumerator();
		while(enum.hasMoreElements()) {
			int selected = ((Integer)enum.nextElement()).intValue();
			Path path = browserModel.getEntry(selected);
			if(path.attributes.isFile()) {
				Editor editor = new Editor();
				editor.open(path);
			}
		}
	}

	public void gotoButtonClicked(Object sender) {
		log.debug("folderButtonClicked");
		CDGotoController controller = new CDGotoController(this.workdir());
		NSApplication.sharedApplication().beginSheet(controller.window(), //sheet
		    this.window(), //docwindow
		    controller, //modal delegate
		    new NSSelector("gotoSheetDidEnd",
		        new Class[]{NSPanel.class, int.class, Object.class}), // did end selector
		    this.workdir()); //contextInfo
	}
	
	public void fileButtonClicked(Object sender) {
		log.debug("fileButtonClicked");
		CDFileController controller = new CDFileController();
		NSApplication.sharedApplication().beginSheet(controller.window(), //sheet
													 this.window(), //docwindow
													 controller, //modal delegate
													 new NSSelector("newFileSheetDidEnd",
																	new Class[]{NSPanel.class, int.class, Object.class}), // did end selector
													 this.workdir()); //contextInfo
	}
	
	public void folderButtonClicked(Object sender) {
		log.debug("folderButtonClicked");
		CDFolderController controller = new CDFolderController();
		NSApplication.sharedApplication().beginSheet(controller.window(), //sheet
													 this.window(), //docwindow
													 controller, //modal delegate
													 new NSSelector("newFolderSheetDidEnd",
																	new Class[]{NSPanel.class, int.class, Object.class}), // did end selector
													 this.workdir()); //contextInfo
	}
	
	public void infoButtonClicked(Object sender) {
		log.debug("infoButtonClicked");
		NSEnumerator enum = browserTable.selectedRowEnumerator();
		List files = new ArrayList();
		while(enum.hasMoreElements()) {
			int selected = ((Integer)enum.nextElement()).intValue();
			files.add(browserModel.getEntry(selected));
		}
		CDInfoController controller = new CDInfoController(files);
		controller.window().makeKeyAndOrderFront(null);
	}

	public void deleteButtonClicked(Object sender) {
		log.debug("deleteButtonClicked");
		NSEnumerator enum = browserTable.selectedRowEnumerator();
		Vector files = new Vector();
		StringBuffer alertText = new StringBuffer(NSBundle.localizedString("Really delete the following files? This cannot be undone.", "Confirm deleting files."));
		while(enum.hasMoreElements()) {
			int selected = ((Integer)enum.nextElement()).intValue();
			Path p = (Path)browserModel.getEntry(selected);
			files.add(p);
			alertText.append("\n- "+p.getName());
		}
		NSAlertPanel.beginCriticalAlertSheet(NSBundle.localizedString("Delete", "Alert sheet title"), //title
		    NSBundle.localizedString("Delete", "Alert sheet default button"), // defaultbutton
		    NSBundle.localizedString("Cancel", "Alert sheet alternate button"), //alternative button
		    null, //other button
		    this.window(), //window
		    this, //delegate
		    new NSSelector
		        ("deleteSheetDidEnd",
		            new Class[]
		            {
			            NSWindow.class, int.class, Object.class
		            }), // end selector
		    null, // dismiss selector
		    files, // contextInfo
		    alertText.toString());
	}

	public void deleteSheetDidEnd(NSWindow sheet, int returnCode, Object contextInfo) {
		log.debug("deleteSheetDidEnd");
		sheet.orderOut(null);
		switch(returnCode) {
			case (NSAlertPanel.DefaultReturn):
				Vector files = (Vector)contextInfo;
				if(files.size() > 0) {
					Iterator i = files.iterator();
					Path p = null;
					while(i.hasNext()) {
						p = (Path)i.next();
						p.delete();
					}
					p.getParent().list(true);
				}
				break;
			case (NSAlertPanel.AlternateReturn):
				break;
		}
	}

	public void refreshButtonClicked(Object sender) {
		log.debug("refreshButtonClicked");
		this.browserTable.deselectAll(sender);
		this.workdir().list(true);
	}

	public void downloadAsButtonClicked(Object sender) {
		Session session = this.workdir().getSession().copy();
		NSEnumerator enum = browserTable.selectedRowEnumerator();
		while(enum.hasMoreElements()) {
			Path path = ((Path)browserModel.getEntry(((Integer)enum.nextElement()).intValue())).copy(session);
			NSSavePanel panel = NSSavePanel.savePanel();
			NSSelector setMessageSelector =
			    new NSSelector("setMessage", new Class[]{String.class});
			if(setMessageSelector.implementedByClass(NSOpenPanel.class)) {
				panel.setMessage(NSBundle.localizedString("Download the selected file to...", ""));
			}
			NSSelector setNameFieldLabelSelector =
			    new NSSelector("setNameFieldLabel", new Class[]{String.class});
			if(setNameFieldLabelSelector.implementedByClass(NSOpenPanel.class)) {
				panel.setNameFieldLabel(NSBundle.localizedString("Download As:", ""));
			}
			panel.setPrompt(NSBundle.localizedString("Download", ""));
			panel.setTitle(NSBundle.localizedString("Download", ""));
			panel.setCanCreateDirectories(true);
			panel.beginSheetForDirectory(null,
			    path.getLocal().getName(),
			    this.window(),
			    this,
			    new NSSelector("saveAsPanelDidEnd", new Class[]{NSOpenPanel.class, int.class, Object.class}),
			    path);
		}
	}

	public void saveAsPanelDidEnd(NSSavePanel sheet, int returnCode, Object contextInfo) {
		switch(returnCode) {
			case (NSAlertPanel.DefaultReturn):
				String filename = null;
				if((filename = sheet.filename()) != null) {
					Path path = (Path)contextInfo;
					path.setLocal(new Local(filename));
					Queue q = new DownloadQueue();
					q.addRoot(path);
					CDQueueController.instance().startItem(q);
				}
				break;
			case (NSAlertPanel.AlternateReturn):
				break;
		}
	}

	public void syncButtonClicked(Object sender) {
		log.debug("syncButtonClicked");
		NSOpenPanel panel = NSOpenPanel.openPanel();
		Path workdir = this.workdir();
		Path selection = workdir.copy(workdir.getSession().copy());
		if(browserTable.numberOfSelectedRows() == 1) {
			selection = this.browserModel.getEntry(browserTable.selectedRow()).copy(workdir.getSession().copy());
		}
		panel.setCanChooseDirectories(selection.attributes.isDirectory());
		panel.setCanChooseFiles(selection.attributes.isFile());
		panel.setCanCreateDirectories(true);
		panel.setAllowsMultipleSelection(false);
		NSSelector setMessageSelector =
		    new NSSelector("setMessage", new Class[]{String.class});
		if(setMessageSelector.implementedByClass(NSOpenPanel.class)) {
			panel.setMessage(NSBundle.localizedString("Synchronize", "")
			    +" "+selection.getName()+" "
			    +NSBundle.localizedString("with", "")+"...");
		}
		panel.setPrompt(NSBundle.localizedString("Choose", ""));
		panel.setTitle(NSBundle.localizedString("Synchronize", ""));
		panel.beginSheetForDirectory(null,
		    null,
		    null,
		    this.window(), //parent window
		    this,
		    new NSSelector("syncPanelDidEnd", new Class[]{NSOpenPanel.class, int.class, Object.class}),
		    selection //context info
		);
	}

	public void syncPanelDidEnd(NSOpenPanel sheet, int returnCode, Object contextInfo) {
		sheet.orderOut(null);
		switch(returnCode) {
			case (NSAlertPanel.DefaultReturn):
				Path selection = (Path)contextInfo;
				if(sheet.filenames().count() > 0) {
					Path sync = selection.copy(selection.getSession().copy());
					sync.setLocal(new Local((String)sheet.filenames().lastObject()));
					Queue q = new SyncQueue((Observer)this);
					q.addRoot(sync);
					CDQueueController.instance().startItem(q);
				}
				break;
			case (NSAlertPanel.AlternateReturn):
				break;
		}
	}

	public void downloadButtonClicked(Object sender) {
		NSEnumerator enum = browserTable.selectedRowEnumerator();
		Queue q = new DownloadQueue();
		Session session = this.workdir().getSession().copy();
		while(enum.hasMoreElements()) {
			q.addRoot(((Path)browserModel.getEntry(((Integer)enum.nextElement()).intValue())).copy(session));
		}
		CDQueueController.instance().startItem(q);
	}

	public void uploadButtonClicked(Object sender) {
		log.debug("uploadButtonClicked");
		NSOpenPanel panel = NSOpenPanel.openPanel();
		panel.setCanChooseDirectories(true);
		panel.setCanChooseFiles(true);
		panel.setAllowsMultipleSelection(true);
		panel.setPrompt(NSBundle.localizedString("Upload", ""));
		panel.setTitle(NSBundle.localizedString("Upload", ""));
		panel.beginSheetForDirectory(null,
		    null,
		    null,
		    this.window(),
		    this,
		    new NSSelector("uploadPanelDidEnd", new Class[]{NSOpenPanel.class, int.class, Object.class}),
		    null);
	}

	public void uploadPanelDidEnd(NSOpenPanel sheet, int returnCode, Object contextInfo) {
		sheet.orderOut(null);
		switch(returnCode) {
			case (NSAlertPanel.DefaultReturn):
				Path workdir = this.workdir();
				// selected files on the local filesystem
				NSArray selected = sheet.filenames();
				java.util.Enumeration enumerator = selected.objectEnumerator();
				Queue q = new UploadQueue((Observer)this);
				Session session = workdir.getSession().copy();
				while(enumerator.hasMoreElements()) {
					q.addRoot(PathFactory.createPath(session,
					    workdir.getAbsolute(),
					    new Local((String)enumerator.nextElement())));
				}
				CDQueueController.instance().startItem(q);
				break;
			case (NSAlertPanel.AlternateReturn):
				break;
		}
	}

	public void insideButtonClicked(Object sender) {
		log.debug("insideButtonClicked");
		this.browserTableRowDoubleClicked(sender);
	}

	public void backButtonClicked(Object sender) {
		log.debug("backButtonClicked");
		this.workdir().getSession().getPreviousPath().list();
	}

	public void upButtonClicked(Object sender) {
		log.debug("upButtonClicked");
		this.workdir().getParent().list();
	}

	public void connectButtonClicked(Object sender) {
		log.debug("connectButtonClicked");
		CDConnectionController controller = new CDConnectionController(this);
		NSApplication.sharedApplication().beginSheet(controller.window(), //sheet
		    this.window(), //docwindow
		    controller, //modal delegate
		    new NSSelector("connectionSheetDidEnd",
		        new Class[]{NSWindow.class, int.class, Object.class}), // did end selector
		    null); //contextInfo
	}

	public void disconnectButtonClicked(Object sender) {
		this.unmount(new NSSelector("unmountSheetDidEnd",
		    new Class[]{NSWindow.class, int.class, Object.class}), null // end selector
		);
	}

	public boolean isMounted() {
		return this.workdir() != null;
	}

	public boolean isConnected() {
		boolean connected = false;
		if(this.isMounted()) {
			connected = this.workdir().getSession().isConnected();
		}
		log.info("Connected:"+connected);
		return connected;
	}

	public void paste(Object sender) {
		log.debug("paste");
		NSPasteboard pboard = NSPasteboard.pasteboardWithName("QueuePBoard");
		if(pboard.availableTypeFromArray(new NSArray("QueuePBoardType")) != null) {
			Object o = pboard.propertyListForType("QueuePBoardType");// get the data from paste board
			if(o != null) {
				NSArray elements = (NSArray)o;
				for(int i = 0; i < elements.count(); i++) {
					NSDictionary dict = (NSDictionary)elements.objectAtIndex(i);
					Queue q = Queue.createQueue(dict);
					Path workdir = this.workdir();
					for(Iterator iter = q.getRoots().iterator(); iter.hasNext();) {
						Path p = (Path)iter.next();
						PathFactory.createPath(workdir.getSession(), p.getAbsolute()).rename(workdir.getAbsolute()+"/"+p.getName());
						p.getParent().invalidate();
						workdir.list(true);
					}
				}
				pboard.setPropertyListForType(null, "QueuePBoardType");
				this.browserTable.reloadData();
			}
		}
	}

	public void copy(Object sender) {
		if(browserTable.selectedRow() != -1) {
			NSMutableArray queueDictionaries = new NSMutableArray();
			Session session = this.workdir().getSession().copy();
			Queue q = new DownloadQueue();
			NSEnumerator enum = browserTable.selectedRowEnumerator();
			while(enum.hasMoreElements()) {
				Path path = this.browserModel.getEntry(((Integer)enum.nextElement()).intValue());
				q.addRoot(path.copy(session));
			}
			queueDictionaries.addObject(q.getAsDictionary());
			// Writing data for private use when the item gets dragged to the transfer queue.
			NSPasteboard queuePboard = NSPasteboard.pasteboardWithName("QueuePBoard");
			queuePboard.declareTypes(new NSArray("QueuePBoardType"), null);
			if(queuePboard.setPropertyListForType(queueDictionaries, "QueuePBoardType")) {
				log.debug("QueuePBoardType data sucessfully written to pasteboard");
			}
			Path p = this.browserModel.getEntry(browserTable.selectedRow());
			NSPasteboard pboard = NSPasteboard.pasteboardWithName(NSPasteboard.GeneralPboard);
			pboard.declareTypes(new NSArray(NSPasteboard.StringPboardType), null);
			if(!pboard.setStringForType(p.getAbsolute(), NSPasteboard.StringPboardType)) {
				log.error("Error writing absolute path of selected item to NSPasteboard.StringPboardType.");
			}
		}
	}

	public void copyURLButtonClicked(Object sender) {
		log.debug("copyURLButtonClicked");
		Host h = this.workdir().getSession().getHost();
		NSPasteboard pboard = NSPasteboard.pasteboardWithName(NSPasteboard.GeneralPboard);
		pboard.declareTypes(new NSArray(NSPasteboard.StringPboardType), null);
		if(!pboard.setStringForType(h.getURL()+h.getDefaultPath(), NSPasteboard.StringPboardType)) {
			log.error("Error writing URL to NSPasteboard.StringPboardType.");
		}
	}

	private Path workdir() {
		return this.workdir;
	}

	public void unmount() {
		if(this.isMounted()) {
			this.workdir().getSession().close();
		}
	}

	public void mount(NSScriptCommand command) {
		log.debug("mount:"+command);
		NSDictionary args = command.evaluatedArguments();
		Host host = new Host((String)args.objectForKey("Protocol"),
		    (String)args.objectForKey("Host"),
		    Integer.parseInt((String)args.objectForKey("Port")),
		    new Login((String)args.objectForKey("Host"), (String)args.objectForKey("Username"), (String)args.objectForKey("Password"), false),
		    (String)args.objectForKey("InitialPath"));
		this.mount(host);
	}

	public void mount(final Host host) {
		log.debug("mount:"+host);
		if(this.unmount(new NSSelector("mountSheetDidEnd",
		    new Class[]{NSWindow.class, int.class, Object.class}), host// end selector
		)) {
			{
				this.window().setTitle(host.getProtocol()+":"+host.getHostname());
				File bookmark = new File(HISTORY_FOLDER+"/"+host.getHostname()+".duck");
				CDBookmarkTableDataSource.instance().exportBookmark(host, bookmark);
				this.window().setRepresentedFilename(bookmark.getAbsolutePath());
			}

			TranscriptFactory.addImpl(host.getHostname(), new CDTranscriptImpl(logView));

			Session session = SessionFactory.createSession(host);
			session.addObserver((Observer)this);

			if(session instanceof ch.cyberduck.core.sftp.SFTPSession) {
				try {
					host.setHostKeyVerificationController(new CDHostKeyController(this));
				}
				catch(com.sshtools.j2ssh.transport.InvalidHostFileException e) {
					//This exception is thrown whenever an exception occurs open or reading from the host file.
					this.beginSheet(NSAlertPanel.criticalAlertPanel(NSBundle.localizedString("Error", "Alert sheet title"), //title
					    NSBundle.localizedString("Could not open or read the host file", "Alert sheet text")+": "+e.getMessage(), // message
					    NSBundle.localizedString("OK", "Alert default button"), // defaultbutton
					    null, //alternative button
					    null //other button
					));
				}
			}
			host.getLogin().setController(new CDLoginController(this));
			session.mount();
		}
	}

	public void mountSheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
		this.unmountSheetDidEnd(sheet, returncode, contextInfo);
		if(returncode == NSAlertPanel.DefaultReturn) {
			this.mount((Host)contextInfo);
		}
	}

	/**
	 * @return True if the unmount process has finished, false if the user has to agree first to close the connection
	 */
	public boolean unmount(NSSelector selector, Object context) {
		log.debug("unmount");
		if(this.isConnected()) {
			NSAlertPanel.beginCriticalAlertSheet(NSBundle.localizedString("Disconnect from", "Alert sheet title")+" "+this.workdir().getSession().getHost().getHostname(), //title
			    NSBundle.localizedString("Disconnect", "Alert sheet default button"), // defaultbutton
			    NSBundle.localizedString("Cancel", "Alert sheet alternate button"), // alternate button
			    null, //other button
			    this.window(), //window
			    this, //delegate
			    selector,
			    null, // dismiss selector
			    context, // context
			    NSBundle.localizedString("The connection will be closed.", "Alert sheet text") // message
			);
			return false;
		}
		return true;
	}

	public boolean loadDataRepresentation(NSData data, String type) {
		if(type.equals("Cyberduck Bookmark")) {
			String[] errorString = new String[]{null};
			Object propertyListFromXMLData =
			    NSPropertyListSerialization.propertyListFromData(data,
			        NSPropertyListSerialization.PropertyListImmutable,
			        new int[]{NSPropertyListSerialization.PropertyListXMLFormat},
			        errorString);
			if(errorString[0] != null) {
				log.error("Problem reading bookmark file: "+errorString[0]);
			}
			else {
				log.debug("Successfully read bookmark file: "+propertyListFromXMLData);
			}
			if(propertyListFromXMLData instanceof NSDictionary) {
				this.mount(new Host((NSDictionary)propertyListFromXMLData));
			}
			return true;
		}
		return false;
	}

	public NSData dataRepresentationOfType(String type) {
		if(this.isMounted()) {
			if(type.equals("Cyberduck Bookmark")) {
				Host bookmark = this.workdir().getSession().getHost();
				NSMutableData collection = new NSMutableData();
				String[] errorString = new String[]{null};
				collection.appendData(NSPropertyListSerialization.dataFromPropertyList(bookmark.getAsDictionary(),
				    NSPropertyListSerialization.PropertyListXMLFormat,
				    errorString));
				if(errorString[0] != null) {
					log.error("Problem writing bookmark file: "+errorString[0]);
				}
				return collection;
			}
		}
		return null;
	}

	// ----------------------------------------------------------
	// Window delegate methods
	// ----------------------------------------------------------

	public static int applicationShouldTerminate(NSApplication app) {
		// Determine if there are any open connections
		NSArray windows = NSApplication.sharedApplication().windows();
		int count = windows.count();
		// Determine if there are any open connections
		while(0 != count--) {
			NSWindow window = (NSWindow)windows.objectAtIndex(count);
			CDBrowserController controller = CDBrowserController.controllerForWindow(window);
			if(null != controller) {
				if(!controller.unmount(new NSSelector("terminateReviewSheetDidEnd",
				    new Class[]{NSWindow.class, int.class, Object.class}),
				    null)) {
					return NSApplication.TerminateLater;
				}
			}
		}
		return CDQueueController.applicationShouldTerminate(app);
	}

	public boolean windowShouldClose(NSWindow sender) {
		return this.unmount(new NSSelector("closeSheetDidEnd",
		    new Class[]{NSWindow.class, int.class, Object.class}), null // end selector
		);
	}

	public void unmountSheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
		sheet.orderOut(null);
		if(returncode == NSAlertPanel.DefaultReturn) {
			this.unmount();
		}
		if(returncode == NSAlertPanel.AlternateReturn) {
			//
		}
	}

	public void closeSheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
		this.unmountSheetDidEnd(sheet, returncode, contextInfo);
		if(returncode == NSAlertPanel.DefaultReturn) {
			this.window().close();
		}
		if(returncode == NSAlertPanel.AlternateReturn) {
			//
		}
	}

	public void terminateReviewSheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
		this.closeSheetDidEnd(sheet, returncode, contextInfo);
		if(returncode == NSAlertPanel.DefaultReturn) { //Disconnect
			CDBrowserController.applicationShouldTerminate(null);
		}
		if(returncode == NSAlertPanel.AlternateReturn) { //Cancel
			NSApplication.sharedApplication().replyToApplicationShouldTerminate(false);
		}
	}

	public void windowWillClose(NSNotification notification) {
		log.debug("windowWillClose");
		if(this.isMounted()) {
			this.workdir().getSession().deleteObserver((Observer)this);
		}
		NSNotificationCenter.defaultCenter().removeObserver(this);
		this.bookmarkDrawer.close();
		this.logDrawer.close();
		instances.removeObject(this);
	}

	public boolean validateMenuItem(NSMenuItem item) {
		if(item.action().name().equals("paste:")) {
			if(this.isMounted()) {
				NSPasteboard pboard = NSPasteboard.pasteboardWithName("QueuePBoard");
				if(pboard.availableTypeFromArray(new NSArray("QueuePBoardType")) != null
				    && pboard.propertyListForType("QueuePBoardType") != null) {
					NSArray elements = (NSArray)pboard.propertyListForType("QueuePBoardType");
					for(int i = 0; i < elements.count(); i++) {
						NSDictionary dict = (NSDictionary)elements.objectAtIndex(i);
						Queue q = Queue.createQueue(dict);
						if(q.numberOfRoots() == 1)
							item.setTitle(NSBundle.localizedString("Paste", "Menu item")+" \""+q.getRoot().getName()+"\"");
						else {
							item.setTitle(NSBundle.localizedString("Paste", "Menu item")
							    +" "+q.numberOfRoots()+" "+
							    NSBundle.localizedString("files", ""));
						}
					}
				}
				else {
					item.setTitle(NSBundle.localizedString("Paste", "Menu item"));
				}
			}
		}
		if(item.action().name().equals("copy:")) {
			if(this.isMounted() && browserTable.selectedRow() != -1) {
				if(browserTable.numberOfSelectedRows() == 1) {
					Path p = (Path)browserModel.getEntry(browserTable.selectedRow());
					item.setTitle(NSBundle.localizedString("Copy", "Menu item")+" \""+p.getName()+"\"");
				}
				else
					item.setTitle(NSBundle.localizedString("Copy", "Menu item")
					    +" "+browserTable.numberOfSelectedRows()+" "+
					    NSBundle.localizedString("files", ""));
			}
			else
				item.setTitle(NSBundle.localizedString("Copy", "Menu item"));
		}
		return this.validateItem(item.action().name());
	}

	private boolean validateItem(String identifier) {
		if(identifier.equals("copy:")) {
			return this.isMounted() && browserTable.selectedRow() != -1;
		}
		if(identifier.equals("paste:")) {
			NSPasteboard pboard = NSPasteboard.pasteboardWithName("QueuePBoard");
			return this.isMounted()
			    && pboard.availableTypeFromArray(new NSArray("QueuePBoardType")) != null
			    && pboard.propertyListForType("QueuePBoardType") != null;
		}
		if(identifier.equals("addBookmarkButtonClicked:")) {
			return true;
		}
		if(identifier.equals("removeBookmarkButtonClicked:")) {
			return bookmarkTable.numberOfSelectedRows() == 1;
		}
		if(identifier.equals("editBookmarkButtonClicked:")) {
			return bookmarkTable.numberOfSelectedRows() == 1;
		}
		if(identifier.equals("Edit") || identifier.equals("editButtonClicked:")) {
			if(this.isMounted() && browserModel.numberOfRowsInTableView(browserTable) > 0 && browserTable.selectedRow() != -1) {
				Path p = (Path)browserModel.getEntry(browserTable.selectedRow());
				String editorPath = null;
				NSSelector absolutePathForAppBundleWithIdentifierSelector =
				    new NSSelector("absolutePathForAppBundleWithIdentifier", new Class[]{String.class});
				if(absolutePathForAppBundleWithIdentifierSelector.implementedByClass(NSWorkspace.class)) {
					editorPath = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(Preferences.instance().getProperty("editor.bundleIdentifier"));
				}
				return p.attributes.isFile() && editorPath != null;
			}
			return false;
		}
		if(identifier.equals("gotoButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("Get Info") || identifier.equals("infoButtonClicked:")) {
			return this.isMounted() && browserTable.selectedRow() != -1;
		}
		if(identifier.equals("New Folder") || identifier.equals("folderButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("New File") || identifier.equals("fileButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("Delete") || identifier.equals("deleteButtonClicked:")) {
			return this.isMounted() && browserTable.selectedRow() != -1;
		}
		if(identifier.equals("Refresh") || identifier.equals("refreshButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("Download") || identifier.equals("downloadButtonClicked:")) {
			return this.isMounted() && browserTable.selectedRow() != -1;
		}
		if(identifier.equals("Upload") || identifier.equals("downloadButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("Synchronize") || identifier.equals("syncButtonClicked:")) {
			return this.isMounted() && browserTable.numberOfSelectedRows() <= 1;
		}
		if(identifier.equals("Download As") || identifier.equals("downloadAsButtonClicked:")) {
			return this.isMounted() && browserTable.numberOfSelectedRows() == 1;
		}
		if(identifier.equals("insideButtonClicked:")) {
			return this.isMounted() && browserTable.selectedRow() != -1;
		}
		if(identifier.equals("upButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("backButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("copyURLButtonClicked:")) {
			return this.isMounted();
		}
		if(identifier.equals("Disconnect")) {
			return this.isMounted() && this.workdir().getSession().isConnected();
		}
		return true; // by default everything is enabled
	}

	// ----------------------------------------------------------
	// Toolbar Delegate
	// ----------------------------------------------------------

	public boolean validateToolbarItem(NSToolbarItem item) {
		//	log.debug("validateToolbarItem:"+item.label());
		boolean enabled = pathPopupItems.size() > 0;
		this.backButton.setEnabled(enabled);
		this.upButton.setEnabled(enabled);
		this.pathPopupButton.setEnabled(enabled);
		this.pathPopupButton.setEnabled(enabled);
		this.searchField.setEnabled(enabled);
		return this.validateItem(item.itemIdentifier());
	}

	public NSToolbarItem toolbarItemForItemIdentifier(NSToolbar toolbar, String itemIdentifier, boolean flag) {
		NSToolbarItem item = new NSToolbarItem(itemIdentifier);
		if(itemIdentifier.equals("New Connection")) {
			item.setLabel(NSBundle.localizedString("New Connection", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("New Connection", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Connect to remote host", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("connect.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("connectButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Bookmarks")) {
			item.setLabel(NSBundle.localizedString("Bookmarks", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Bookmarks", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Toggle Bookmarks", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("drawer.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("toggleBookmarkDrawer", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Quick Connect")) {
			item.setLabel(NSBundle.localizedString("Quick Connect", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Quick Connect", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Connect to server", "Toolbar item tooltip"));
			item.setView(quickConnectPopup);
			item.setMinSize(quickConnectPopup.frame().size());
			item.setMaxSize(quickConnectPopup.frame().size());
			return item;
		}
		if(itemIdentifier.equals("Refresh")) {
			item.setLabel(NSBundle.localizedString("Refresh", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Refresh", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Refresh directory listing", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("reload.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("refreshButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Download")) {
			item.setLabel(NSBundle.localizedString("Download", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Download", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Download file", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("downloadFile.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("downloadButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Upload")) {
			item.setLabel(NSBundle.localizedString("Upload", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Upload", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Upload local file to the remote host", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("uploadFile.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("uploadButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Synchronize")) {
			item.setLabel(NSBundle.localizedString("Sync", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Synchronize", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Synchronize local with remote files", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("sync32.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("syncButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Get Info")) {
			item.setLabel(NSBundle.localizedString("Get Info", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Get Info", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Show file attributes", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("info.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("infoButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Edit")) {
			item.setLabel(NSBundle.localizedString("Edit", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Edit", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Edit file in external editor", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("pencil.tiff"));
			NSSelector absolutePathForAppBundleWithIdentifierSelector =
			    new NSSelector("absolutePathForAppBundleWithIdentifier", new Class[]{String.class});
			if(absolutePathForAppBundleWithIdentifierSelector.implementedByClass(NSWorkspace.class)) {
				String editorPath = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(Preferences.instance().getProperty("editor.bundleIdentifier"));
				if(editorPath != null) {
					item.setImage(NSWorkspace.sharedWorkspace().iconForFile(editorPath));
				}
			}
			item.setTarget(this);
			item.setAction(new NSSelector("editButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Delete")) {
			item.setLabel(NSBundle.localizedString("Delete", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Delete", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Delete file", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("deleteFile.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("deleteButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("New Folder")) {
			item.setLabel(NSBundle.localizedString("New Folder", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("New Folder", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Create New Folder", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("newfolder.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("folderButtonClicked", new Class[]{Object.class}));
			return item;
		}
		if(itemIdentifier.equals("Disconnect")) {
			item.setLabel(NSBundle.localizedString("Disconnect", "Toolbar item"));
			item.setPaletteLabel(NSBundle.localizedString("Disconnect", "Toolbar item"));
			item.setToolTip(NSBundle.localizedString("Disconnect from server", "Toolbar item tooltip"));
			item.setImage(NSImage.imageNamed("eject.tiff"));
			item.setTarget(this);
			item.setAction(new NSSelector("disconnectButtonClicked", new Class[]{Object.class}));
			return item;
		}
		// itemIdent refered to a toolbar item that is not provide or supported by us or cocoa.
		// Returning null will inform the toolbar this kind of item is not supported.
		return null;
	}


	public NSArray toolbarDefaultItemIdentifiers(NSToolbar toolbar) {
		return new NSArray(new Object[]{
			"New Connection",
			NSToolbarItem.SeparatorItemIdentifier,
			"Bookmarks",
			"Quick Connect",
			"Refresh",
			"Get Info",
			"Edit",
			"Download",
			"Upload",
			"Synchronize",
			NSToolbarItem.FlexibleSpaceItemIdentifier,
			"Disconnect"
		});
	}

	public NSArray toolbarAllowedItemIdentifiers(NSToolbar toolbar) {
		return new NSArray(new Object[]{
			"New Connection",
			"Bookmarks",
			"Quick Connect",
			"Refresh",
			"Synchronize",
			"Download",
			"Upload",
			"Edit",
			"Delete",
			"New Folder",
			"Get Info",
			"Disconnect",
			NSToolbarItem.CustomizeToolbarItemIdentifier,
			NSToolbarItem.SpaceItemIdentifier,
			NSToolbarItem.SeparatorItemIdentifier,
			NSToolbarItem.FlexibleSpaceItemIdentifier
		});
	}

	// ----------------------------------------------------------
	// Browser Model
	// ----------------------------------------------------------

	private static final NSImage SYMLINK_ICON = NSImage.imageNamed("symlink.tiff");
	private static final NSImage FOLDER_ICON = NSImage.imageNamed("folder16.tiff");
	private static final NSImage NOT_FOUND_ICON = NSImage.imageNamed("notfound.tiff");

	private static NSMutableParagraphStyle lineBreakByTruncatingMiddleParagraph = new NSMutableParagraphStyle();

	static {
		lineBreakByTruncatingMiddleParagraph.setLineBreakMode(NSParagraphStyle.LineBreakByTruncatingMiddle);
	}

	private static final NSDictionary TABLE_CELL_PARAGRAPH_DICTIONARY = new NSDictionary(new Object[]{lineBreakByTruncatingMiddleParagraph},
	    new Object[]{NSAttributedString.ParagraphStyleAttributeName});

	private class CDBrowserTableDataSource extends CDTableDataSource {
		private List fullData;
		private List currentData;

		public CDBrowserTableDataSource() {
			this.fullData = new ArrayList();
			this.currentData = new ArrayList();
		}

		public int numberOfRowsInTableView(NSTableView tableView) {
			return currentData.size();
		}

		public Object tableViewObjectValueForLocation(NSTableView tableView, NSTableColumn tableColumn, int row) {
			if(row < this.numberOfRowsInTableView(tableView)) {
				String identifier = (String)tableColumn.identifier();
				Path p = (Path)this.currentData.get(row);
				if(identifier.equals("TYPE")) {
					NSImage icon;
					if(p.attributes.isSymbolicLink()) {
						icon = SYMLINK_ICON;
					}
					else if(p.attributes.isDirectory()) {
						icon = FOLDER_ICON;
					}
					else if(p.attributes.isFile()) {
						icon = CDIconCache.instance().get(p.getExtension());
					}
					else {
						icon = NOT_FOUND_ICON;
					}
					icon.setSize(new NSSize(16f, 16f));
					return icon;
				}
				if(identifier.equals("FILENAME")) {
					return new NSAttributedString(p.getName(), TABLE_CELL_PARAGRAPH_DICTIONARY);
				}
				if(identifier.equals("SIZE")) {
					return new NSAttributedString(Status.getSizeAsString(p.status.getSize()), TABLE_CELL_PARAGRAPH_DICTIONARY);
				}
				if(identifier.equals("MODIFIED")) {
					return new NSGregorianDate((double)p.attributes.getTimestamp().getTime()/1000,
					    NSDate.DateFor1970);
				}
				if(identifier.equals("OWNER")) {
					return new NSAttributedString(p.attributes.getOwner(), TABLE_CELL_PARAGRAPH_DICTIONARY);
				}
				if(identifier.equals("PERMISSIONS")) {
					return new NSAttributedString(p.attributes.getPermission().toString(), TABLE_CELL_PARAGRAPH_DICTIONARY);
				}
				if(identifier.equals("TOOLTIP")) {
					return p.getAbsolute()+"\n"
					    +Status.getSizeAsString(p.status.getSize())+"\n"
					    +p.attributes.getTimestampAsString();
				}
				throw new IllegalArgumentException("Unknown identifier: "+identifier);
			}
			return null;
		}

		/**
		 * The files dragged from the browser to the Finder
		 */
		private Path[] promisedDragPaths;

		// ----------------------------------------------------------
		// Drop methods
		// ----------------------------------------------------------

		public int tableViewValidateDrop(NSTableView tableView, NSDraggingInfo info, int row, int operation) {
			log.info("tableViewValidateDrop:row:"+row+",operation:"+operation);
			if(isMounted()) {
				if(info.draggingPasteboard().availableTypeFromArray(new NSArray(NSPasteboard.FilenamesPboardType)) != null) {
					if(row != -1 && row < tableView.numberOfRows()) {
						Path selected = this.getEntry(row);
						if(selected.attributes.isDirectory()) {
							tableView.setDropRowAndDropOperation(row, NSTableView.DropOn);
							return NSDraggingInfo.DragOperationCopy;
						}
					}
					tableView.setDropRowAndDropOperation(-1, NSTableView.DropOn);
					return NSDraggingInfo.DragOperationCopy;
				}
				NSPasteboard pboard = NSPasteboard.pasteboardWithName("QueuePBoard");
				if(pboard.availableTypeFromArray(new NSArray("QueuePBoardType")) != null) {
					if(row != -1 && row < tableView.numberOfRows()) {
						Path selected = this.getEntry(row);
						if(selected.attributes.isDirectory()) {
							tableView.setDropRowAndDropOperation(row, NSTableView.DropOn);
							return NSDraggingInfo.DragOperationMove;
						}
					}
				}
			}
			return NSDraggingInfo.DragOperationNone;
		}

		public boolean tableViewAcceptDrop(NSTableView tableView, NSDraggingInfo info, int row, int operation) {
			log.debug("tableViewAcceptDrop:row:"+row+",operation:"+operation);
			NSPasteboard infoPboard = info.draggingPasteboard();
			if(infoPboard.availableTypeFromArray(new NSArray(NSPasteboard.FilenamesPboardType)) != null) {
				NSArray filesList = (NSArray)infoPboard.propertyListForType(NSPasteboard.FilenamesPboardType);
				Queue q = new UploadQueue((Observer)CDBrowserController.this);
				Session session = workdir().getSession().copy();
				for(int i = 0; i < filesList.count(); i++) {
					log.debug(filesList.objectAtIndex(i));
					Path p = null;
					if(row != -1) {
						p = PathFactory.createPath(session,
						    this.getEntry(row).getAbsolute(),
						    new Local((String)filesList.objectAtIndex(i)));
					}
					else {
						p = PathFactory.createPath(session,
						    workdir().getAbsolute(),
						    new Local((String)filesList.objectAtIndex(i)));
					}
					q.addRoot(p);
				}
				if(q.numberOfRoots() > 0) {
					CDQueueController.instance().startItem(q);
				}
				return true;
			}
			else if(row != -1 && row < tableView.numberOfRows()) {
				NSPasteboard pboard = NSPasteboard.pasteboardWithName("QueuePBoard");
				log.debug("availableTypeFromArray:QueuePBoardType: "+pboard.availableTypeFromArray(new NSArray("QueuePBoardType")));
				if(pboard.availableTypeFromArray(new NSArray("QueuePBoardType")) != null) {
					NSArray elements = (NSArray)pboard.propertyListForType("QueuePBoardType");// get the data from pasteboard
					for(int i = 0; i < elements.count(); i++) {
						NSDictionary dict = (NSDictionary)elements.objectAtIndex(i);
						Path parent = this.getEntry(row);
						if(parent.attributes.isDirectory()) {
							Queue q = Queue.createQueue(dict);
							for(Iterator iter = q.getRoots().iterator(); iter.hasNext();) {
								Path p = (Path)iter.next();
								PathFactory.createPath(parent.getSession(), p.getAbsolute()).rename(parent.getAbsolute()+"/"+p.getName());
							}
							tableView.deselectAll(null);
							workdir().list(true);
							return true;
						}
					}
				}
			}
			return false;
		}


		// ----------------------------------------------------------
		// Drag methods
		// ----------------------------------------------------------

		/**
		 * Invoked by tableView after it has been determined that a drag should begin, but before the drag has been started.
		 * The drag image and other drag-related information will be set up and provided by the table view once this call
		 * returns with true.
		 *
		 * @param rows is the list of row numbers that will be participating in the drag.
		 * @return To refuse the drag, return false. To start a drag, return true and place the drag data onto pboard
		 *         (data, owner, and so on).
		 */
		public boolean tableViewWriteRowsToPasteboard(NSTableView tableView, NSArray rows, NSPasteboard pboard) {
			log.debug("tableViewWriteRowsToPasteboard:"+rows);
			if(rows.count() > 0) {
				this.promisedDragPaths = new Path[rows.count()];
				// The fileTypes argument is the list of fileTypes being promised. The array elements can consist of file extensions and HFS types encoded with the NSHFSFileTypes method fileTypeForHFSTypeCode. If promising a directory of files, only include the top directory in the array.
				NSMutableArray fileTypes = new NSMutableArray();
				NSMutableArray queueDictionaries = new NSMutableArray();
				// declare our dragged type in the paste board
				pboard.declareTypes(new NSArray(NSPasteboard.FilesPromisePboardType), null);
				Queue q = new DownloadQueue();
				Session session = workdir().getSession().copy();
				for(int i = 0; i < rows.count(); i++) {
					promisedDragPaths[i] = (Path)this.getEntry(((Integer)rows.objectAtIndex(i)).intValue()).copy(session);
					if(promisedDragPaths[i].attributes.isFile()) {
						// fileTypes.addObject(NSPathUtilities.FileTypeRegular);
						if(promisedDragPaths[i].getExtension() != null) {
							fileTypes.addObject(promisedDragPaths[i].getExtension());
						}
						else {
							fileTypes.addObject(NSPathUtilities.FileTypeUnknown);
						}
					}
					else if(promisedDragPaths[i].attributes.isDirectory()) {
						// fileTypes.addObject(NSPathUtilities.FileTypeDirectory);
						fileTypes.addObject("'fldr'");
					}
					else {
						fileTypes.addObject(NSPathUtilities.FileTypeUnknown);
					}
					q.addRoot(promisedDragPaths[i]);
				}
				queueDictionaries.addObject(q.getAsDictionary());
				// Writing data for private use when the item gets dragged to the transfer queue.
				NSPasteboard queuePboard = NSPasteboard.pasteboardWithName("QueuePBoard");
				queuePboard.declareTypes(new NSArray("QueuePBoardType"), null);
				if(queuePboard.setPropertyListForType(queueDictionaries, "QueuePBoardType")) {
					log.debug("QueuePBoardType data sucessfully written to pasteboard");
				}

				NSEvent event = NSApplication.sharedApplication().currentEvent();
				NSPoint dragPosition = tableView.convertPointFromView(event.locationInWindow(), null);
				NSRect imageRect = new NSRect(new NSPoint(dragPosition.x()-16, dragPosition.y()-16), new NSSize(32, 32));

				tableView.dragPromisedFilesOfTypes(fileTypes, imageRect, this, true, event);
			}
			// we return false because we don't want the table to draw the drag image
			return false;
		}

		/**
		 * @return the names (not full paths) of the files that the receiver promises to create at dropDestination.
		 *         This method is invoked when the drop has been accepted by the destination and the destination, in the case of another
		 *         Cocoa application, invokes the NSDraggingInfo method namesOfPromisedFilesDroppedAtDestination. For long operations,
		 *         you can cache dropDestination and defer the creation of the files until the finishedDraggingImage method to avoid
		 *         blocking the destination application.
		 */
		public NSArray namesOfPromisedFilesDroppedAtDestination(java.net.URL dropDestination) {
			log.debug("namesOfPromisedFilesDroppedAtDestination:"+dropDestination);
			NSMutableArray promisedDragNames = new NSMutableArray();
			if(null != dropDestination) {
				Queue q = new DownloadQueue();
				for(int i = 0; i < promisedDragPaths.length; i++) {
					try {
						this.promisedDragPaths[i].setLocal(new Local(java.net.URLDecoder.decode(dropDestination.getPath(), "UTF-8"),
						    this.promisedDragPaths[i].getName()));
						q.addRoot(this.promisedDragPaths[i]);
						promisedDragNames.addObject(this.promisedDragPaths[i].getName());
					}
					catch(java.io.UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
				}
				if(q.numberOfRoots() > 0) {
					CDQueueController.instance().startItem(q);
				}
			}
			return promisedDragNames;
		}

		// ----------------------------------------------------------
		// Delegate methods
		// ----------------------------------------------------------

		public boolean isSortedAscending() {
			return this.sortAscending;
		}

		public NSTableColumn selectedColumn() {
			return this.selectedColumn;
		}

		private boolean sortAscending = true;
		private NSTableColumn selectedColumn = null;

		public void sort(NSTableColumn tableColumn, final boolean ascending) {
			final int higher = ascending ? 1 : -1;
			final int lower = ascending ? -1 : 1;
			if(tableColumn.identifier().equals("TYPE")) {
				Collections.sort(this.values(),
				    new Comparator() {
					    public int compare(Object o1, Object o2) {
						    Path p1 = (Path)o1;
						    Path p2 = (Path)o2;
						    if(p1.attributes.isDirectory() && p2.attributes.isDirectory()) {
							    return 0;
						    }
						    if(p1.attributes.isFile() && p2.attributes.isFile()) {
							    return 0;
						    }
						    if(p1.attributes.isFile()) {
							    return higher;
						    }
						    return lower;
					    }
				    });
			}
			else if(tableColumn.identifier().equals("FILENAME")) {
				Collections.sort(this.values(),
				    new Comparator() {
					    public int compare(Object o1, Object o2) {
						    Path p1 = (Path)o1;
						    Path p2 = (Path)o2;
						    if(ascending) {
							    return p1.getName().compareToIgnoreCase(p2.getName());
						    }
						    else {
							    return -p1.getName().compareToIgnoreCase(p2.getName());
						    }
					    }
				    });
			}
			else if(tableColumn.identifier().equals("SIZE")) {
				Collections.sort(this.values(),
				    new Comparator() {
					    public int compare(Object o1, Object o2) {
						    long p1 = ((Path)o1).status.getSize();
						    long p2 = ((Path)o2).status.getSize();
						    if(p1 > p2) {
							    return higher;
						    }
						    else if(p1 < p2) {
							    return lower;
						    }
						    else {
							    return 0;
						    }
					    }
				    });
			}
			else if(tableColumn.identifier().equals("MODIFIED")) {
				Collections.sort(this.values(),
				    new Comparator() {
					    public int compare(Object o1, Object o2) {
						    Path p1 = (Path)o1;
						    Path p2 = (Path)o2;
						    if(ascending) {
							    return p1.attributes.getTimestamp().compareTo(p2.attributes.getTimestamp());
						    }
						    else {
							    return -p1.attributes.getTimestamp().compareTo(p2.attributes.getTimestamp());
						    }
					    }
				    });
			}
			else if(tableColumn.identifier().equals("OWNER")) {
				Collections.sort(this.values(),
				    new Comparator() {
					    public int compare(Object o1, Object o2) {
						    Path p1 = (Path)o1;
						    Path p2 = (Path)o2;
						    if(ascending) {
							    return p1.attributes.getOwner().compareToIgnoreCase(p2.attributes.getOwner());
						    }
						    else {
							    return -p1.attributes.getOwner().compareToIgnoreCase(p2.attributes.getOwner());
						    }
					    }
				    });
			}
		}

		public void tableViewDidClickTableColumn(NSTableView tableView, NSTableColumn tableColumn) {
			log.debug("tableViewDidClickTableColumn");
			if(this.selectedColumn == tableColumn) {
				this.sortAscending = !this.sortAscending;
			}
			else {
				if(selectedColumn != null) {
					tableView.setIndicatorImage(null, selectedColumn);
				}
				this.selectedColumn = tableColumn;
			}
			tableView.setIndicatorImage(this.sortAscending ? NSImage.imageNamed("NSAscendingSortIndicator") : NSImage.imageNamed("NSDescendingSortIndicator"), tableColumn);
			this.sort(tableColumn, sortAscending);
			tableView.reloadData();
		}

		// ----------------------------------------------------------
		// Data access
		// ----------------------------------------------------------

		public void clear() {
			this.fullData.clear();
			this.currentData.clear();
		}

		public void setData(List data) {
			this.fullData = data;
			this.currentData = data;
		}

		public Path getEntry(int row) {
			if(row < currentData.size()) {
				return (Path)this.currentData.get(row);
			}
			return null;
		}

		public void removeEntry(Path o) {
			int frow = fullData.indexOf(o);
			if(frow < fullData.size()) {
				fullData.remove(frow);
			}
			int crow = currentData.indexOf(o);
			if(crow < currentData.size()) {
				currentData.remove(crow);
			}
		}

		public int indexOf(Path o) {
			return currentData.indexOf(o);
		}

		public void setActiveSet(List currentData) {
			this.currentData = currentData;
		}

		public List values() {
			return this.fullData;
		}
	}
}
