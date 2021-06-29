package com.palmergames.bukkit.towny.tasks;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyTimerHandler;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.AlreadyRegisteredException;
import com.palmergames.bukkit.towny.exceptions.MojangException;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.util.BukkitTools;

import java.io.IOException;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author LlmDl
 * 
 */
public class GatherResidentUUIDTask implements Runnable {

	@SuppressWarnings("unused")
	private Towny plugin;
	private final static Queue<Resident> queue = new ConcurrentLinkedQueue<>();
	private static boolean offlineModeDetected = true;

	/**
	 * @param plugin reference to Towny
	 */
	public GatherResidentUUIDTask(Towny plugin) {

		super();
		this.plugin = plugin;
	}

	@Override
	public void run() {
		if (queue.isEmpty()) {
			TownyTimerHandler.toggleGatherResidentUUIDTask(false);
			return;
		}
		Resident resident = queue.poll();
		if (resident.isNPC()) // This is one of our own NPC residents, lets give them a UUID if they don't already have one.
			applyUUID(resident, UUID.randomUUID(), "Towny");
		
		UUID uuid = BukkitTools.getUUIDSafely(resident.getName()); // Get a UUID from the server's playercache without calling to Mojang. 

		if (uuid != null) { // The player has been online recently enough to be in the cache.
			if (!offlineModeDetected && uuid.version() == 4) // True offline servers return a v3 UUID instead of v4.
				offlineModeDetected = true;
			
			applyUUID(resident, uuid, "cache"); 

		} else if (!offlineModeDetected) { // If the server is in true offline mode the following test would result always return 204, wiping the database.
			applyUUID(resident, uuid, "cache"); 			
		}
	}
	
	public static void addResident(Resident resident) {
		queue.add(resident);
	}

	private void applyUUID(Resident resident, UUID uuid, String source) {
		resident.setUUID(uuid);
		try {
			TownyUniverse.getInstance().registerResidentUUID(resident);
		} catch (AlreadyRegisteredException e) {
			TownyMessaging.sendErrorMsg(String.format("Error registering resident UUID. Resident '%s' already has a UUID registered!", resident.getName()));
		}
		resident.save();
		TownySettings.incrementUUIDCount();
		TownyMessaging.sendDebugMsg("UUID stored for " + resident.getName() + " received from " + source + ". Progress: " + TownySettings.getUUIDPercent() + ".");
	}
	
	public static void markOfflineMode() {
		offlineModeDetected = true;
	}
	
}
