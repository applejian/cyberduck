package ch.cyberduck.core;

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

import ch.cyberduck.core.ftp.FTPPath;
import ch.cyberduck.core.sftp.SFTPPath;
import ch.cyberduck.core.http.HTTPPath;

import ch.cyberduck.core.ftp.FTPSession;
import ch.cyberduck.core.http.HTTPSession;
import ch.cyberduck.core.sftp.SFTPSession;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import com.apple.cocoa.foundation.NSDictionary;
import com.apple.cocoa.foundation.NSMutableDictionary;
import com.apple.cocoa.foundation.NSArray;
import com.apple.cocoa.foundation.NSMutableArray;
import org.apache.log4j.Logger;

/**
* Used to this multiple connections. <code>this.start()</code> will
 * start the the connections in the order the have been added to me.
 * Useful for actions where the order of the taken actions
 * is important, i.e. deleting directories or uploading directories.
 * @version $Id$
 */
public class Queue extends Observable implements Observer { //Thread {
    private static Logger log = Logger.getLogger(Queue.class);
	
    /**
    * Estimation time till end of processing
     */
    private Timer leftTimer;
    /**
		* File transfer pogress
     */
    private Timer progressTimer;
    /**
		* Time left since start of processing
     */
    private Timer elapsedTimer;
	
    Calendar calendar = Calendar.getInstance();
	
    public static final int KIND_DOWNLOAD = 0;
    public static final int KIND_UPLOAD = 1;
    /**
		* What kind of this, either KIND_DOWNLOAD or KIND_UPLOAD
     */
    private int kind;
    /**
		* Number of completed jobs in the this
     */
    private int completedJobs;
    /**
		* The file currently beeing processed in the this
     */
    private Path currentJob;
	
    /**
		* This is a list of root paths. This is either a directory
	 * or a regular file itself.
     */
//    private List roots; //Path
    private Path root; //Path
	
	public Path getRoot() {
		return this.root;
	}
	
    /**
		* This has the same size as the roots and contains the root
     * path itself and all subelements (in case of a directory) 
     */
    private List jobs;
//    private List[] jobs;
    
    /**
		* The this has been canceled from processing for any reason
     */
    private boolean running;
	
//	private boolean initialized;
	
    /*
     * 	current speed (bytes/second)
     */
    private long speed;
    /*
     * overall speed (bytes/second)
     */
	//    private transient double overall;

    private int timeLeft = -1;
	private long current = -1;
	private long size = -1;
	
	private String status;
	
	public Queue(Path root, int kind) {
		this.root = root;
		this.kind = kind;
//		List roots = new ArrayList(); 
//		roots.add(root);
//		this.init(roots, kind);
		this.init();
	}
	
//    public Queue(List roots, int kind) {
//		log.debug("New this with "+roots.size()+" root elements");
//		this.init(roots, kind);
//	}
	
//	private void init(List roots, int kind) {
//		this.roots = roots;
//		this.kind = kind;
//		this.validator = validator;
//		this.currentJob = (Path)roots.get(0);
//    }
	
	public Queue(NSDictionary dict) {
		this.kind = ((Integer)dict.objectForKey("Kind")).intValue();
		Host host = new Host((NSDictionary)dict.objectForKey("Host"));
		NSArray elements = (NSArray)dict.objectForKey("Roots");
		for(int i = 0; i < elements.count(); i++) {
			//todo Path Factory Pattern
			if(host.getProtocol().equalsIgnoreCase(Session.HTTP)) {
				this.root = new HTTPPath((HTTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i));
//				this.addRoot(new HTTPPath((HTTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i)));
			}
			//  if(host.getProtocol().equalsIgnoreCase(Session.HTTPS)) {
			//            return new HTTPSession(this);
			//        }
			else if(host.getProtocol().equalsIgnoreCase(Session.FTP)) {
				this.root = new FTPPath((FTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i));
//				this.addRoot(new FTPPath((FTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i)));
			}
			else if(host.getProtocol().equalsIgnoreCase(Session.SFTP)) {
				this.root = new SFTPPath((SFTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i));
//				this.addRoot(new SFTPPath((SFTPSession)host.createSession(), (NSDictionary)elements.objectAtIndex(i)));
			}
			else {
				log.error("Unknown protocol");
			}
		}
		this.status = (String)dict.objectForKey("Status");
		this.size = ((Long)dict.objectForKey("Size")).longValue();
		this.current = ((Long)dict.objectForKey("Current")).longValue();
		this.init();
	}
	
	private void init() {
	    this.calendar.set(Calendar.HOUR, 0);
		this.calendar.set(Calendar.MINUTE, 0);
		this.calendar.set(Calendar.SECOND, 0);		
	}
	
	public NSDictionary getAsDictionary() {
		NSMutableDictionary dict = new NSMutableDictionary();
		dict.setObjectForKey(this.status, "Status");
		dict.setObjectForKey(new Integer(this.kind()), "Kind");
		dict.setObjectForKey(new Long(this.getSize()), "Size");
		dict.setObjectForKey(new Long(this.getCurrent()), "Current");
		dict.setObjectForKey(this.root.getHost().getAsDictionary(), "Host");
//		Iterator i = this.getRoots().iterator();
		NSMutableArray list = new NSMutableArray();
//		while(i.hasNext()) {
//			list.addObject(((Path)i.next()).getAsDictionary());
//		}
		list.addObject(this.root.getAsDictionary());
		dict.setObjectForKey(list, "Roots");
		return dict;
	}
	
    public int kind() {
		return this.kind;
    }
	
    public void callObservers(Object arg) {
		//	log.debug(this.countObservers()+" observers known.");
        this.setChanged();
		this.notifyObservers(arg);
    }
	
    public void update(Observable o, Object arg) {
		//Forwarding all messages from the current file's status to my observers
		if(arg instanceof Message) {
			Message msg = (Message)arg;
			if(msg.getTitle().equals(Message.PROGRESS) || msg.getTitle().equals(Message.ERROR))
				this.status = (String)msg.getContent();
		}
		this.callObservers(arg);
	}
	
    private void process() {
		log.debug("process");
		//		int i = 0;
		//		Iterator rootIterator = roots.iterator();
		//		while(rootIterator.hasNext()) {
		//Iterating over all the files in the this
		if(!this.isCanceled()) {
//			Path root = (Path)rootIterator.next();
			root.getSession().addObserver(this);
			//			this.callObservers(root);
			Iterator elements = jobs.iterator();
//			Iterator elements = jobs[i].iterator();
			while(elements.hasNext() && !this.isCanceled()) {
				this.progressTimer.start();
				this.leftTimer.start();
				
				this.currentJob = (Path)elements.next();
				this.currentJob.status.setResume(root.status.isResume());
				this.currentJob.status.addObserver(this);

				switch(kind) {
					case KIND_DOWNLOAD:
						currentJob.download();
						break;
					case KIND_UPLOAD:
						currentJob.upload();
						break;
				}
				if(currentJob.status.isComplete()) {
					this.completedJobs++;
					//					this.current += currentJob.status.getCurrent();
				}
				this.currentJob.status.deleteObserver(this);
				
				this.progressTimer.stop();
				this.leftTimer.stop();
			}
			root.getSession().deleteObserver(this);
			if(this.isEmpty()) {
				root.getSession().close();
			}
		}
		//			i++;
		//		}
	}
	
	/**
		* Process the this. All files will be downloaded or uploaded rerspectively.
	 * @param validator A callback class where the user can decide what to do if
	 * the file already exists at the download location
	 */
	public void start(final Validator validator) {
		log.debug("start");
//		this.validator = validator;
		new Thread() {
			public void run() {
//				reset();
				running = true;
				startTimers();
				jobs = new ArrayList();
				jobs = new ArrayList();
				if(validator.validate(root, kind)) {
					log.debug("Filling this of root element "+root);
					root.getSession().addObserver(Queue.this);
					root.fillQueue(jobs, kind);
					root.getSession().deleteObserver(Queue.this);
				}
				elapsedTimer.start();
				process();
				elapsedTimer.stop();
				running = false;
			}
		}.start();
	}
	
    public void cancel() {
		this.running = false;
//		if(currentJob != null)
			currentJob.status.setCanceled(true);
    }
	
//	public boolean isInitialized() {
//		return this.initialized;
//	}
	
	public boolean isRunning() {
		return this.running;
	}
	
    public boolean isCanceled() {
		return !this.isRunning();
    }
	
//	public Path getCurrentJob() {
//		return this.currentJob;
//	}
	
    /**
		* @return Number of remaining items to be processed in the this.
     */
    public int remainingJobs() {
//		log.debug("remainingJobs:");
		return this.numberOfJobs() - this.completedJobs();
    }
	
    /**
		* @return Number of completed (totally transferred) items in the this.
     */
    public int completedJobs() {
//		log.debug("completedJobs:"+completedJobs);
		return this.completedJobs;
    }

    /**
		* @return Number of jobs in the this.
     */
    public int numberOfJobs() {
		int value = 1;
		if(this.isRunning())
			value = jobs.size();
//		int value = 0;
//		for(int i = 0; i < jobs.length; i ++) {
//			value += this.jobs[i].size();
//		}
//		log.debug("numberOfJobs:"+value);
		return value;
    }
	
    /**
		* @return rue if all items in the this have been processed sucessfully.
     */
    public boolean isEmpty() {
		return this.remainingJobs() == 0;
    }
	
	public String getStatus() {
		return this.status;
		/*
		if(this.isRunning()) {
			if(Queue.KIND_DOWNLOAD == this.kind())
				return this.getElapsedTime()+" "+"Downloading "+this.getRoot().getDecodedName()+" ("+(this.completedJobs())+" of "+(this.numberOfJobs())+")";
			return this.getElapsedTime()+" "+"Uploading "+this.getRoot().getDecodedName()+" ("+(this.completedJobs())+" of "+(this.numberOfJobs())+")";
		}
		if(this.isEmpty()) {
			return this.getElapsedTime()+" "+
			"Complete ("+(this.completedJobs())+
			" of "+
			(this.numberOfJobs())+
			")";
		}
		return this.getElapsedTime()+" "+
		"Incomplete ("+(this.completedJobs())+
		" of "+
		(this.numberOfJobs())+
		")";
		 */
	}
	
	public String getSizeAsString() {
		return Status.getSizeAsString(this.getSize());
//		if(null == size)
//			return "";
//		return size;
    }
	
    /**
		* @return The cummulative file size of all files remaining in the this
     */
    public long getSize() {
//		if(this.size <= 0)
//			this.size = this.calculateTotalSize();
//		return this.size;
		return this.calculateTotalSize();
    }
	
    public long calculateTotalSize() {
//		this.size = -1;
		if(this.isRunning()) {
			this.size = 0; //todo
			Iterator elements = jobs.iterator();
			while(elements.hasNext()) {
				this.size += ((Path)elements.next()).status.getSize();
			}
		}
		//		for(int i = 0; i < jobs.length; i ++) {
//			if(null != jobs[i]) {
//				Iterator elements = jobs[i].iterator();
//				while(elements.hasNext()) {
//					value += ((Path)elements.next()).status.getSize();
//				}
//			}
//		}
//		log.debug("calculateTotalSize:"+value);
//		return value;
		return this.size;
    }
	
	private long calculateCurrentSize() {
//		int value = -1;
		if(this.isRunning()) {
			this.current = 0; //todo
			Iterator elements = jobs.iterator();
			while(elements.hasNext()) {
				this.current += ((Path)elements.next()).status.getCurrent();
			}
		}
		//		for(int i = 0; i < jobs.length; i ++) {
//			if(null != jobs[i]) {
//				Iterator elements = jobs[i].iterator();
//				while(elements.hasNext()) {
//					value += ((Path)elements.next()).status.getCurrent();
//				}
//			}
//		}
//		log.debug("calculateCurrentSize:"+value);
//		return value;
		return this.current;
    }
	
    public String getCurrentAsString() {
		return Status.getSizeAsString(this.getCurrent());
//		if(null == current)
//			return "";
//		return current;
    }

    /**
		* @return The number of bytes already processed of all elements in the whole this.
     */
    public long getCurrent() {
		return this.calculateCurrentSize();
//		return (this.current + currentJob.status.getCurrent());	
    }
	
    /**
		* @return double current bytes/second
     */
    public String getSpeedAsString() {
		if(this.isRunning())
			return Status.getSizeAsString(this.getSpeed());
		return "";
//		String speed = Status.getSizeAsString(this.getSpeed());
//		if(null == speed)
//			return "";
//		return speed+"/sec";
    }
	
	public long getSpeed() {
		return this.speed;
	}
    
    private void setSpeed(long s) {
		this.speed = s;
		this.callObservers(new Message(Message.DATA));
    }
	
    private void setTimeLeft(int seconds) {
        this.timeLeft = seconds;
		this.callObservers(new Message(Message.DATA));
    }
	
    public String getTimeLeft() {
		if(this.isRunning()) {
//        String message = "";
        //@todo: implementation of better 'time left' management.
//        if(this.timeLeft != -1) {
            if(this.timeLeft >= 60) {
                return (int)this.timeLeft/60 + " minutes remaining.";
            }
            else {
                return this.timeLeft + " seconds remaining.";
            }
//        }
//        return message;
		}
		return "";
    }
	
	public String getElapsedTime() {
		if(calendar.get(Calendar.HOUR) > 0) {
			return this.parseTime(calendar.get(Calendar.HOUR)) 
			+":"
			+ parseTime(calendar.get(Calendar.MINUTE)) 
			+":"
			+ parseTime(calendar.get(Calendar.SECOND));
		}
		else {
			return this.parseTime(calendar.get(Calendar.MINUTE)) 
			+":"
			+ parseTime(calendar.get(Calendar.SECOND));
		}
	}
	
    private String parseTime(int t) {
		if(t > 9) {
			return String.valueOf(t);
        }
        else {
            return "0" + t;
		}
    }
	
    
    /**
		* @return double bytes per seconds transfered since the connection has been opened
     */
	//    private double getOverall() {
 //	return this.overall;
 //    }
	
	//private void setOverall(double s) {
 //    this.overall = s;
 //
 //    this.callObservers(new Message(Message.SPEED, "Current: "
 //				   + Status.parseDouble(this.getSpeed()/1024) + "kB/s, Overall: "
 //				   + Status.parseDouble(this.getOverall()/1024) + " kB/s. "+this.getTimeLeft()));
 //}

	/*
private void reset() {
    this.speed = -1;
	//    this.overall = 0;
    this.timeLeft = -1;
    this.completedJobs = 0;
    this.calendar.set(Calendar.HOUR, 0);
    this.calendar.set(Calendar.MINUTE, 0);
    this.calendar.set(Calendar.SECOND, 0);
//	this.callObservers(new Message(Message.DATA));
}
*/

private void startTimers() {
    this.calendar.set(Calendar.HOUR, 0);
    this.calendar.set(Calendar.MINUTE, 0);
    this.calendar.set(Calendar.SECOND, 0);
    this.elapsedTimer = new Timer(1000,
								  new ActionListener() {
									  int seconds = 0;
									  int minutes = 0;
									  int hours = 0;
									  public void actionPerformed(ActionEvent event) {
										  seconds++;
										  // calendar.set(year, mont, date, hour, minute, second)
			// >= one hour
										  if(seconds >= 3600) {
											  hours = (int)(seconds/60/60);
											  minutes = (int)((seconds - hours*60*60)/60);
											  calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), hours, minutes, seconds - minutes*60);
										  }
										  else {
											  // >= one minute
											  if(seconds >= 60) {
												  minutes = (int)(seconds/60);
												  calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), calendar.get(Calendar.HOUR), minutes, seconds - minutes*60);
											  }
											  // only seconds
											  else {
												  calendar.set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DATE), calendar.get(Calendar.HOUR), calendar.get(Calendar.MINUTE), seconds);
											  }
										  }
										  Queue.this.callObservers(new Message(Message.CLOCK));
									  }
								  }
								  );
	
    /*
     Timer overallSpeedTimer = new Timer(4000,
										 new ActionListener() {
											 Vector overall = new Vector();
											 double current;
											 double last;
											 public void actionPerformed(ActionEvent e) {
												 current = currentJob.status.getCurrent();
												 if(current <= 0) {
													 setOverall(0);
												 }
												 else {
													 overall.add(new Double((current - last)/4)); // bytes transferred for the last 4 seconds
													 Iterator iterator = overall.iterator();
													 double sum = 0;
													 while(iterator.hasNext()) {
														 Double s = (Double)iterator.next();
														 sum = sum + s.doubleValue();
													 }
													 setOverall((sum/overall.size()));
													 last = current;
													 //                        log.debug("overallSpeed " + sum/overall.size()/1024 + " KBytes/sec");
												 }
											 }
										 }
										 );
     */
	
    this.progressTimer = new Timer(500,
								   new ActionListener() {
									   int i = 0;
									   long current;
									   long last;
									   long[] speeds = new long[8];
									   public void actionPerformed(ActionEvent e) {
										   long diff = 0;
										   current = currentJob.status.getCurrent(); // Bytes
										   if(current <= 0) {
											   setSpeed(0);
										   }
										   else {
											   speeds[i] = (current - last)*2; // Bytes per second
											   i++; last = current;
											   if(i == 8) { // wir wollen immer den schnitt der letzten vier sekunden
												   i = 0;
											   }
											   for (int k = 0; k < speeds.length; k++) {
												   diff = diff + speeds[k]; // summe der differenzen zwischen einer halben sekunde
											   }
											   Queue.this.setSpeed((diff/speeds.length)); //Bytes per second
										   }
										   
									   }
								   }
								   );
	
    this.leftTimer = new Timer(1000,
							   new ActionListener() {
								   public void actionPerformed(ActionEvent e) {
//									   if(getSpeed() > 0)
									   Queue.this.setTimeLeft((int)((Queue.this.getSize() - currentJob.status.getCurrent())/getSpeed()));
//									   else
//										   Queue.this.setTimeLeft(-1);
								   }
							   }
							   );
}
}