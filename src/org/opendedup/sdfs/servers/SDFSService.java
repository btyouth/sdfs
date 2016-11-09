/*******************************************************************************
 * Copyright (C) 2016 Sam Silverberg sam.silverberg@gmail.com	
 *
 * This file is part of OpenDedupe SDFS.
 *
 * OpenDedupe SDFS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * OpenDedupe SDFS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package org.opendedup.sdfs.servers;

import java.util.ArrayList;

import org.opendedup.hashing.HashFunctionPool;
import org.opendedup.hashing.VariableHashEngine;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.mtools.BloomFDisk;
import org.opendedup.sdfs.Config;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.DedupFileStore;
import org.opendedup.sdfs.filestore.MetaFileStore;
import org.opendedup.sdfs.filestore.gc.StandAloneGCScheduler;
import org.opendedup.sdfs.mgmt.MgmtWebServer;
import org.opendedup.sdfs.network.NetworkDSEServer;
import org.opendedup.sdfs.notification.SDFSEvent;
import org.opendedup.util.OSValidator;

public class SDFSService {
	String configFile;

	private NetworkDSEServer ndServer = null;
	private ArrayList<String> volumes;
	private static boolean stopped = false;

	public static boolean isStopped() {
		return stopped;
	}

	public SDFSService(String configFile, ArrayList<String> volumes) {

		this.configFile = configFile;
		this.volumes = volumes;
		System.out.println("Running Program SDFS Version " + Main.version);

		System.out.println("reading config file = " + this.configFile);
	}

	public void start(boolean useSSL, int port) throws Exception {
		Config.parseSDFSConfigFile(this.configFile);
		if (port != -1)
			Main.sdfsCliPort = port;
		if (Main.version.startsWith("0") || Main.version.startsWith("1")) {
			System.err.println("This version is not backwards compatible with previous versions of SDFS");
			System.err.println("Exiting");
			System.exit(-1);
		}
		SDFSLogger.getLog().debug("############# SDFSService Starting ##################");

		Main.mountEvent = SDFSEvent
				.mountEvent("SDFS Version [" + Main.version + "] Mounting Volume from " + this.configFile);
		if (HashFunctionPool.max_hash_cluster > 1)
			SDFSLogger.getLog().info("HashFunction Min Block Size=" + VariableHashEngine.minLen + " Max Block Size="
					+ VariableHashEngine.maxLen);
		Main.DSEID = Main.volume.getSerialNumber();
		SDFSLogger.getLog().debug("HCServiceProxy Starting");
		HCServiceProxy.init(volumes);
		SDFSLogger.getLog().debug("HCServiceProxy Started");
		MgmtWebServer.start(useSSL);
		if (Main.chunkStoreLocal) {
			try {

				if (Main.volume == null && !Main.runCompact) {
					ndServer = new NetworkDSEServer();
					new Thread(ndServer).start();
				}
			} catch (Exception e) {
				SDFSLogger.getLog().error("Unable to initialize volume ", e);
				System.err.println("Unable to initialize Hash Chunk Service");
				e.printStackTrace();
				System.exit(-1);
			}
			if (Main.runCompact) {
				this.stop();
				System.exit(0);
			}

			Main.pFullSched = new StandAloneGCScheduler();
		}

		Main.mountEvent.endEvent("Volume Mounted");
		try {
			if (Main.volume.getName() == null)
				Main.volume.setName(configFile);
			Main.volume.setClosedGracefully(false);
			Config.writeSDFSConfigFile(configFile);
		} catch (Exception e) {
			SDFSLogger.getLog().error("Unable to write volume config.", e);
		}
		Main.volume.init();
		SDFSLogger.getLog().debug("############### SDFSService Started ##################");
	}

	public void stop() {
		stopped = true;
		SDFSEvent evt = SDFSEvent.umountEvent("Unmounting Volume");
		SDFSLogger.getLog().info("Shutting Down SDFS");
		SDFSLogger.getLog().info("Stopping FDISK scheduler");

		try {
			BloomFDisk.closed = true;
			Main.pFullSched.close();
			Main.pFullSched = null;

		} catch (Exception e) {
		}
		SDFSLogger.getLog().info("Flushing and Closing Write Caches");
		try {
			DedupFileStore.close();
		} catch (Exception e) {
			System.out.println("Dedupe File store did not close correctly");
			SDFSLogger.getLog().error("Dedupe File store did not close correctly", e);
		}
		SDFSLogger.getLog().info("Write Caches Flushed and Closed");
		SDFSLogger.getLog().info("Committing open Files");
		try {
			MetaFileStore.close();
		} catch (Exception e) {
			System.out.println("Meta File store did not close correctly");
			SDFSLogger.getLog().error("Meta File store did not close correctly", e);
		}
		SDFSLogger.getLog().info("Open File Committed");
		SDFSLogger.getLog().info("Writing Config File");

		/*
		 * try { MD5CudaHash.freeMem(); } catch (Exception e) { }
		 */
		try {
		MgmtWebServer.stop();
		}catch(Exception e) {
			System.out.println("Web Server did not close correctly");
			SDFSLogger.getLog().error("Web server did not close correctly", e);
		}
		try {
			if (OSValidator.isUnix()) {
				Process p = Runtime.getRuntime().exec("umount " + Main.volumeMountPoint);
				p.waitFor();
			}
		} catch (Exception e) {
			
		}
		if (Main.chunkStoreLocal) {
			SDFSLogger.getLog().info("######### Shutting down HashStore ###################");
			try {
			HCServiceProxy.close();
			}catch(Exception e) {
				System.out.println("HashStore did not close correctly");
				SDFSLogger.getLog().error("Dedupe File store did not close correctly", e);
			}
			SDFSLogger.getLog().info("######### HashStore Closed ###################");
			Main.volume.setClosedGracefully(true);
			try {
				Config.writeSDFSConfigFile(configFile);
			} catch (Exception e) {

			}
			if (Main.enableNetworkChunkStore && !Main.runCompact) {
				try {
					ndServer.close();
				} catch (Exception e) {
				}
			}
		}
		try {
			Main.volume.setClosedGracefully(true);
			Config.writeSDFSConfigFile(configFile);
		} catch (Throwable e) {
			SDFSLogger.getLog().error("Unable to write volume config.", e);
		}
		evt.endEvent("Volume Unmounted");
		SDFSLogger.getLog().info("SDFS is Shut Down");
	}
}
