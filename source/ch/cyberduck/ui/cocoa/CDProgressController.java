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

import java.util.Observable;
import java.util.Observer;

import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.NSAttributedString;
import com.apple.cocoa.foundation.NSDictionary;
import com.apple.cocoa.foundation.NSObject;
import com.apple.cocoa.foundation.NSBundle;
import com.apple.cocoa.foundation.NSSelector;

import org.apache.log4j.Logger;

import ch.cyberduck.ui.cocoa.growl.Growl;
import ch.cyberduck.core.*;

/**
 * @version $Id$
 */
public class CDProgressController extends NSObject implements Observer {
	private static Logger log = Logger.getLogger(CDProgressController.class);

	private static NSMutableParagraphStyle lineBreakByTruncatingMiddleParagraph = new NSMutableParagraphStyle();
	private static NSMutableParagraphStyle lineBreakByTruncatingTailParagraph = new NSMutableParagraphStyle();

	static {
		lineBreakByTruncatingMiddleParagraph.setLineBreakMode(NSParagraphStyle.LineBreakByTruncatingMiddle);
		lineBreakByTruncatingTailParagraph.setLineBreakMode(NSParagraphStyle.LineBreakByTruncatingTail);
	}

	private static final NSDictionary TRUNCATE_MIDDLE_PARAGRAPH_DICTIONARY = new NSDictionary(new Object[]{lineBreakByTruncatingMiddleParagraph},
	    new Object[]{NSAttributedString.ParagraphStyleAttributeName});
	private static final NSDictionary TRUNCATE_TAIL_PARAGRAPH_DICTIONARY = new NSDictionary(new Object[]{lineBreakByTruncatingTailParagraph},
	    new Object[]{NSAttributedString.ParagraphStyleAttributeName});


	private Queue queue;

	public CDProgressController(Queue queue) {
		this.queue = queue;
		this.queue.addObserver(this);
		//@todo this.queue.deleteObserver(this);
		if(false == NSApplication.loadNibNamed("Progress", this)) {
			log.fatal("Couldn't load Progress.nib");
		}
	}

	public void awakeFromNib() {
		log.debug("awakeFromNib");
		this.filenameField.setAttributedStringValue(new NSAttributedString(this.queue.getName(),
		    TRUNCATE_TAIL_PARAGRAPH_DICTIONARY));
		this.updateProgressfield();
	}

	public void update(Observable o, Object arg) {
		if(arg instanceof Message) {
			log.debug("update:"+arg);
			Message msg = (Message)arg;
			if(msg.getTitle().equals(Message.DATA)) {
				this.updateProgressbar();
				this.updateProgressfield();
			}
			else if(msg.getTitle().equals(Message.PROGRESS)) {
				this.updateProgressfield();
			}
			else if(msg.getTitle().equals(Message.ERROR)) {
				this.alertIcon.setHidden(false);
			}
			else if(msg.getTitle().equals(Message.QUEUE_START)) {
				this.progressBar.setIndeterminate(true);
				this.progressBar.startAnimation(null);
				this.alertIcon.setHidden(true);
			}
			else if(msg.getTitle().equals(Message.QUEUE_STOP)) {
				this.progressBar.setIndeterminate(false);
				this.progressBar.stopAnimation(null);
				if(this.queue.isComplete()) {
					if(this.queue instanceof DownloadQueue) {
						Growl.instance().notify(NSBundle.localizedString("Download complete",
																		 "Growl Notification"),
												this.queue.getName());
						if(Preferences.instance().getProperty("queue.postProcessItemWhenComplete").equals("true")) {
							boolean success = NSWorkspace.sharedWorkspace().openFile(this.queue.getRoot().getLocal().toString());
							log.debug("Success opening file:"+success);
						}
					}
					if(this.queue instanceof UploadQueue) {
						Growl.instance().notify(NSBundle.localizedString("Upload complete",
																		 "Growl Notification"),
												this.queue.getName());
					}
					if(this.queue instanceof SyncQueue) {
						Growl.instance().notify(NSBundle.localizedString("Synchronization complete",
																		 "Growl Notification"),
												this.queue.getName());
					}
					if(Preferences.instance().getProperty("queue.removeItemWhenComplete").equals("true")) {
						CDQueueController.instance().removeItem(this.queue);
					}
				}
			}
		}
	}

	private void updateProgressbar() {
		if(queue.isInitalized()) {
			double progressValue = queue.getCurrent()/queue.getSize();
			this.progressBar.setIndeterminate(false);
			this.progressBar.setMinValue(0);
			this.progressBar.setMaxValue((double)queue.getSize());
			this.progressBar.setDoubleValue((double)queue.getCurrent());
		}
		else {
			this.progressBar.setIndeterminate(true);
		}
	}

	private void updateProgressfield() {
		this.progressField.setAttributedStringValue(new NSAttributedString(queue.getStatusText(),
		    TRUNCATE_MIDDLE_PARAGRAPH_DICTIONARY));
	}

	public Queue getQueue() {
		return this.queue;
	}

	public void alertButtonClicked(NSButton sender) {
		CDQueueController.instance().beginSheet(NSAlertPanel.criticalAlertPanel(NSBundle.localizedString("Error", "Alert sheet title"),
																				this.queue.getErrorText(), // message
																				NSBundle.localizedString("OK", "Alert default button"), // defaultbutton
																				null, //alternative button
																				null //other button
																				),
												true);
	}
		
	private boolean highlighted;

	public void setHighlighted(boolean highlighted) {
		this.highlighted = highlighted;
		if(highlighted) {
			this.filenameField.setTextColor(NSColor.whiteColor());
			this.progressField.setTextColor(NSColor.whiteColor());
		}
		else {
			this.filenameField.setTextColor(NSColor.blackColor());
			this.progressField.setTextColor(NSColor.darkGrayColor());
		}
	}

	public boolean isHighlighted() {
		return this.highlighted;
	}
	
	// ----------------------------------------------------------
	// Outlets
	// ----------------------------------------------------------
	
	private NSTextField filenameField; // IBOutlet

	public void setFilenameField(NSTextField filenameField) {
		this.filenameField = filenameField;
		this.filenameField.setEditable(false);
		this.filenameField.setSelectable(false);
		this.filenameField.setTextColor(NSColor.blackColor());
	}

	private NSTextField progressField; // IBOutlet

	public void setProgressField(NSTextField progressField) {
		this.progressField = progressField;
		this.progressField.setEditable(false);
		this.progressField.setSelectable(false);
		this.progressField.setTextColor(NSColor.darkGrayColor());
	}

	private NSProgressIndicator progressBar; // IBOutlet

	public void setProgressBar(NSProgressIndicator progressBar) {
		this.progressBar = progressBar;
		this.progressBar.setIndeterminate(false);
		this.progressBar.setDisplayedWhenStopped(false);
		this.progressBar.setControlTint(NSProgressIndicator.GraphiteControlTint);
		this.progressBar.setControlTint(NSProgressIndicator.BlueControlTint);
		this.progressBar.setControlSize(NSProgressIndicator.SmallControlSize);
		this.progressBar.setStyle(NSProgressIndicator.ProgressIndicatorBarStyle);
		this.progressBar.setUsesThreadedAnimation(true);
	}

	private NSButton alertIcon; // IBOutlet

	public void setAlertIcon(NSButton alertIcon) {
		this.alertIcon = alertIcon;
		this.alertIcon.setHidden(true);
		this.alertIcon.setTarget(this);
		this.alertIcon.setAction(new NSSelector("alertButtonClicked", new Class[]{Object.class}));
	}

	private NSView progressView; // IBOutlet

	public void setProgressSubview(NSView progressView) {
		this.progressView = progressView;
	}

	public NSView view() {
		return this.progressView;
	}
}

