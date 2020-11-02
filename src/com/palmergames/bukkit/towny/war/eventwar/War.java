package com.palmergames.bukkit.towny.war.eventwar;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.event.EventWarEndEvent;
import com.palmergames.bukkit.towny.event.EventWarPreStartEvent;
import com.palmergames.bukkit.towny.event.EventWarStartEvent;
import com.palmergames.bukkit.towny.exceptions.AlreadyRegisteredException;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.huds.HUDManager;
import com.palmergames.bukkit.towny.object.Coord;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownBlockType;
import com.palmergames.bukkit.towny.object.Translation;
import com.palmergames.bukkit.towny.object.WorldCoord;
import com.palmergames.bukkit.towny.utils.CombatUtil;
import com.palmergames.bukkit.towny.utils.NameGenerator;
import com.palmergames.bukkit.util.BookFactory;
import com.palmergames.bukkit.util.BukkitTools;
import com.palmergames.bukkit.util.ChatTools;
import com.palmergames.bukkit.util.Colors;
import com.palmergames.bukkit.util.ServerBroadCastTimerTask;
import com.palmergames.util.KeyValue;
import com.palmergames.util.KeyValueTable;
import com.palmergames.util.TimeMgmt;
import com.palmergames.util.TimeTools;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FireworkEffect.Type;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class War {
	
	// War Data
	private Hashtable<WorldCoord, Integer> warZone = new Hashtable<>();
	private Hashtable<Resident, Integer> residentLives = new Hashtable<>();
	private Hashtable<Town, Integer> townScores = new Hashtable<>();
	private List<Town> warringTowns = new ArrayList<>();
	private List<Nation> warringNations = new ArrayList<>();
	private List<Resident> warringResidents = new ArrayList<>();
	private List<Player> onlineWarriors = new ArrayList<>();
	private int totalResidentsAtStart = 0;
	private int totalNationsAtStart = 0;
	private double warSpoilsAtStart = 0.0;
	private WarSpoils warSpoils = new WarSpoils();
	private WarType warType;
	private String warName;
	private String newline = "\n";
	public static final SimpleDateFormat warDateFormat = new SimpleDateFormat("MMM d yyyy '@' HH:mm");
	private Towny plugin;
	private boolean warTime = false;
	private List<Integer> warTaskIds = new ArrayList<>();

	/**
	 * Creates a new War instance.
	 * @param plugin - {@link Towny}
	 * @param startDelay - the delay before war will begin
	 */
	public War(Towny plugin, int startDelay, List<Nation> nations, List<Town> towns, List<Resident> residents, WarType warType) {

		
		if (!TownySettings.isUsingEconomy()) {
			TownyMessaging.sendGlobalMessage("War Event cannot function while using_economy: false in the config.yml. Economy Required.");
        	return;
		}

		this.plugin = plugin;
		this.warType = warType;
		
		
		/*
		 * Currently only used to add money to the war spoils.
		 */
		EventWarPreStartEvent preEvent = new EventWarPreStartEvent();
		Bukkit.getServer().getPluginManager().callEvent(preEvent);
		if (preEvent.getWarSpoils() != 0.0)
			warSpoils.deposit(preEvent.getWarSpoils(), "WarSpoils EventWarPreStartEvent Added");

		/*
		 * Takes the given lists and add them to War lists, if they 
		 * meet the requires set out in add(Town) and add(Nation),
		 * based on the WarType.
		 */
		switch(warType) {
			case WORLDWAR:
			case NATIONWAR:
			case CIVILWAR:
				for (Nation nation : nations) {
					if (!nation.isNeutral()) {
						if (add(nation)) {
							warringNations.add(nation);
							TownyMessaging.sendPrefixedNationMessage(nation, Translation.of("msg_war_join_nation", nation.getName()));
						}
					} else if (!TownySettings.isDeclaringNeutral()) {
						nation.setNeutral(false);
						if (add(nation)) {
							warringNations.add(nation);
							TownyMessaging.sendPrefixedNationMessage(nation, Translation.of("msg_war_join_forced", nation.getName()));
						}
					}
				}
				break;
			case TOWNWAR:
			case RIOT:
				for (Town town : towns) {
					// TODO: town neutrality tests here
					if (add(town))
						warringTowns.add(town);
				}
				break;
		}
		

		/*
		 * Make sure that we have enough people/towns/nations involved
		 * for the give WarType.
		 */
		if (!verifyTwoEnemies()) {
			TownyMessaging.sendGlobalMessage("Failed to get the correct number of teams for war to happen! Good-bye!");
			end(false);
			return;
		}
		
		/*
		 * Populate a couple of variables that will help us show the end users
		 * how many residents/nations were at war.
		 */
		totalResidentsAtStart = warringResidents.size();
		totalNationsAtStart = warringNations.size();
		
		/*
		 * Seed the war spoils.
		 */
		try {
			warSpoils.deposit(warType.baseSpoils, "Start of " + warType.getName() + " War - Base Spoils");			
			TownyMessaging.sendGlobalMessage(Translation.of("msg_war_seeding_spoils_with", TownySettings.getBaseSpoilsOfWar()));			
			TownyMessaging.sendGlobalMessage(Translation.of("msg_war_total_seeding_spoils", warSpoils.getHoldingBalance()));
			TownyMessaging.sendGlobalMessage(Translation.of("msg_war_activate_war_hud_tip"));
			
			EventWarStartEvent event = new EventWarStartEvent(warringTowns, warringNations, warSpoilsAtStart);
			Bukkit.getServer().getPluginManager().callEvent(event);
			warSpoilsAtStart = warSpoils.getHoldingBalance();
		} catch (EconomyException e) {
			TownyMessaging.sendErrorMsg("[War] Could not seed spoils of war.");
			end(false);
			return;
		}
		
		/*
		 * Get a name for the war.
		 */
		setWarName();
		
		/*
		 * If things have gotten this far it is reasonable to think we can start the war.
		 */
		setupDelay(startDelay);
	}

	/*
	 * War Start and End Methods.
	 */
	
	/**
	 * Creates a delay before war begins
	 * @param delay - Delay before war begins
	 */
	public void setupDelay(int delay) {

		if (delay <= 0)
			start();
		else {
			// Create a countdown timer
			for (Long t : TimeMgmt.getCountdownDelays(delay, TimeMgmt.defaultCountdownDelays)) {
				// TODO: Add the war name to the message since it spams all players online and/or don't make it spam all online players and only the ones in the war.
				int id = BukkitTools.scheduleAsyncDelayedTask(new ServerBroadCastTimerTask(plugin, Translation.of("default_towny_prefix") + " " + Colors.Red + Translation.of("war_starts_in_x", TimeMgmt.formatCountdownTime(t))), TimeTools.convertToTicks((delay - t)));
				if (id == -1) {
					TownyMessaging.sendErrorMsg("Could not schedule a countdown message for war event.");
					end(false);
				} else
					addTaskId(id);
			}
			// Schedule set up delay
			int id = BukkitTools.scheduleAsyncDelayedTask(new Runnable() {
				
				@Override
				public void run() {
					start();
					
				}
			}, TimeTools.convertToTicks(delay));
			if (id == -1) {
				TownyMessaging.sendErrorMsg("Could not schedule setup delay for war event.");
				end(false);
			} else {
				addTaskId(id);
			}
		}
	}

	/**
	 * Start the war.
	 * 
	 * Starts the timer taxks.
	 */
	public void start() {
		
		outputParticipants();

		warTime = true;

		// Start the WarTimerTask if the war type allows for using townblock HP system.
		if (warType.hasTownBlockHP) {
			int id = BukkitTools.scheduleAsyncRepeatingTask(new WarTimerTask(plugin, this), 0, TimeTools.convertToTicks(5));
			if (id == -1) {
				TownyMessaging.sendErrorMsg("Could not schedule war event loop.");
				end(false);
			} else
				addTaskId(id);
		}
		
		for (Player player : Bukkit.getOnlinePlayers()) {
			Resident resident = null;
			try {
				resident = TownyUniverse.getInstance().getDataSource().getResident(player.getName());
			} catch (NotRegisteredException e) {
				continue;
			}
			if (warringResidents.contains(resident)) {
				player.getInventory().addItem(BookFactory.makeBook(warName, "War Declared", createWarStartBookText()));
				addOnlineWarrior(player);
			}
		}
		
		checkEnd();
		TownyUniverse.getInstance().addWar(this);
	}

	/**
	 * Used at war start and in the /towny war participants command.
	 */
	public void outputParticipants() {
		String name = warName;
		List<String> warParticipants = new ArrayList<>();
		
		switch (warType) {
		case WORLDWAR:
		case NATIONWAR:
		case CIVILWAR:
			Translation.of("msg_war_participants_header");
			for (Nation nation : warringNations) {
				int towns = 0;
				for (Town town : nation.getTowns())
					if (warringTowns.contains(town))
						towns++;
				warParticipants.add(Translation.of("msg_war_participants", nation.getName(), towns));			
			}
			break;
		case TOWNWAR:
			warParticipants.add(Colors.translateColorCodes("&6[War] &eTown Name &f(&bResidents&f)"));
			for (Town town : warringTowns) {
				warParticipants.add(Translation.of("msg_war_participants", town.getName(), town.getResidents().size()));
			}
			break;
		case RIOT:
			warParticipants.add(Colors.translateColorCodes("&6[War] &eResident Name &f(&bLives&f) "));
			for (Resident resident : warringResidents) {
				warParticipants.add(Translation.of("msg_war_participants", resident.getName(), residentLives.get(resident)));
			}
			break;
		}
		for (Nation nation : warringNations) {
			int towns = 0;
			for (Town town : nation.getTowns())
				if (warringTowns.contains(town))
					towns++;
			warParticipants.add(Translation.of("msg_war_participants", nation.getName(), towns));			
		}
		TownyMessaging.sendPlainGlobalMessage(ChatTools.formatTitle(name + " Participants"));

		for (String string : warParticipants)
			TownyMessaging.sendPlainGlobalMessage(string);
		TownyMessaging.sendPlainGlobalMessage(ChatTools.formatTitle("----------------"));
	}
	
	/**
	 * Checks if the end has been reached.
	 */
	public void checkEnd() {

		switch(warType) {
		case WORLDWAR:
		case NATIONWAR:
			if (warringNations.size() <= 1)
				end(true);
			else if (CombatUtil.areAllAllies(warringNations))
				end(true);
			break;
		case CIVILWAR:
			if (warringTowns.size() <= 1)
				end(true);
			break;
		case TOWNWAR:
			if (warringTowns.size() <= 1)
				end(true);
			// TODO: Handle town neutrality.
			break;
		case RIOT:
			if (warringResidents.size() <= 1)
				end(true);
			else if (CombatUtil.areAllFriends(warringResidents))
				end(true);
			break;
		}
	}

	/**
	 * Ends the war.
	 * 
	 * Send the stats to all the players, toggle all the war HUDS.
	 * @param endedSuccessful - False if something has caused the war to finish before it should have.
	 */
	public void end(boolean endedSuccessful) {
		
		/*
		 * Print out stats to players
		 */
		for (Player player : BukkitTools.getOnlinePlayers()) {
			if (player != null)
				TownyMessaging.sendMessage(player, getStats());
		}

		/*
		 * Kill the war huds.
		 */
		removeWarHuds(this);
		
		/*
		 * Pay out the money.
		 */
		if (endedSuccessful)
			awardSpoils();

		/*
		 * Null this war.
		 */
		TownyUniverse.getInstance().removeWar(this);
	}

	/*
	 * Getters and Setters
	 */

	/*
	 * Task Related
	 */
	public List<Integer> getTaskIds() {

		return new ArrayList<>(warTaskIds);
	}
	
	public void addTaskId(int id) {

		warTaskIds.add(id);
	}

	public void clearTaskIds() {

		warTaskIds.clear();
	}

	public void cancelTasks(BukkitScheduler scheduler) {

		for (Integer id : getTaskIds())
			scheduler.cancelTask(id);
		clearTaskIds();
	}
	
	/*
	 * Towny Plugin Related - Unused
	 */
	@Deprecated
	public void setPlugin(Towny plugin) {this.plugin = plugin;}

	@Deprecated
	public Towny getPlugin() {return plugin;}

	 // required while we still have /ta toggle war command.
	@Deprecated
	public boolean isWarTime() {return warTime;}
	
	/*
	 * War Spoils Related
	 */
	public WarSpoils getWarSpoils() {

		return warSpoils;
	}

	public Hashtable<Town, Integer> getTownScores() {
		return townScores;
	}

	/* 
	 * Warzone/Nation/Town/Resident Related
	 */
	public Hashtable<WorldCoord, Integer> getWarZone() {
		return warZone;
	}

	public List<Nation> getWarringNations() {
		return warringNations;
	}
	
	public List<Town> getWarringTowns() {
		return warringTowns;
	}
	
	public List<Resident> getWarringResidents() {
		return warringResidents;
	}
	
	public boolean isWarringNation(Nation nation) {

		return warringNations.contains(nation);
	}

	public boolean isWarringTown(Town town) {

		return warringTowns.contains(town);
	}
	
	public boolean isWarringResident(Resident resident) {

		return warringResidents.contains(resident);
	}
	
	public boolean isWarZone(WorldCoord worldCoord) {

		return warZone.containsKey(worldCoord);
	}

	/*
	 * Online player (onlineWarriors) tracking.
	 */
	public void addOnlineWarrior(Player player) {
		onlineWarriors.add(player);
	}
	
	public void removeOnlineWarrior(Player player) {
		onlineWarriors.remove(player);
	}
	
	public List<Player> getOnlineWarriors() {
		return onlineWarriors;
	}
	
	public WarType getWarType() {
		return warType;
	}

	public int getLives(Resident resident) {
		return residentLives.get(resident);
	}

	/*
	 * Adds towns and nations to the war.
	 */

	/**
	 * Add a nation to war, and all the towns within it.
	 * @param nation {@link Nation} to incorporate into War.
	 * @return false if conditions are not met.
	 */
	private boolean add(Nation nation) {
		if (nation.getEnemies().size() < 1)
			return false;
		int enemies = 0;
		for (Nation enemy : nation.getEnemies()) {
			if (enemy.hasEnemy(nation))
				enemies++;
		}
		if (enemies < 1)
			return false;
		
		int numTowns = 0;
		for (Town town : nation.getTowns()) {
			if (add(town)) {
				warringTowns.add(town);
				townScores.put(town, 0);
				numTowns++;
			}
		}
		// The nation capital must be one of the valid towns for a nation to go to war.
		if (numTowns > 0 && warringTowns.contains(nation.getCapital())) {
			TownyMessaging.sendPrefixedNationMessage(nation, "You have joined a war of type: " + warType.getName());
			return true;
		} else {
			for (Town town : nation.getTowns()) {
				if (warringTowns.contains(town)) {
					warringTowns.remove(town);
					townScores.remove(town);
				}
			}
			return false;
		}
	}

	/**
	 * Add a town to war. Set the townblocks in the town to the correct health.
	 * Add the residents to the war, give them their lives.
	 * @param town {@link Town} to incorporate into war
	 * @return false if conditions are not met.
	 */
	private boolean add(Town town) {
		int numTownBlocks = 0;
		
		/*
		 * With the instanced war system, Towns can only have one on-going war.
		 * TODO: make this a setting which will recover from a crash/shutdown.
		 */
		if (town.hasActiveWar()) {
			TownyMessaging.sendErrorMsg("The town " + town.getName() + " is already involved in a war. They will not take part in the war.");
			return false;
		}
		
		/*
		 * Limit war to towns in worlds with war allowed.
		 */
		try {
			if (!town.getHomeBlock().getWorld().isWarAllowed()) {
				TownyMessaging.sendErrorMsg("The town " + town.getName() + " exists in a world with war disabled. They will not take part in the war.");
				return false;
			}
		} catch (TownyException ignored) {}
		
		/*
		 * Homeblocks are absolutely required for a war with TownBlock HP.
		 */
		if (warType.hasTownBlockHP) {
			if (!town.hasHomeBlock()) {
				TownyMessaging.sendErrorMsg("The town " + town.getName() + " does not have a homeblock. They will not take part in the war.");
				return false;
			}
		}
		
		/*
		 * Even if TownBlock HP is not a factor we 
		 * still need a list of warzone plots.
		 */
		for (TownBlock townBlock : town.getTownBlocks()) {
			if (!townBlock.getWorld().isWarAllowed())
				continue;
			numTownBlocks++;
			if (town.isHomeBlock(townBlock))
				warZone.put(townBlock.getWorldCoord(), TownySettings.getWarzoneHomeBlockHealth());
			else
				warZone.put(townBlock.getWorldCoord(), TownySettings.getWarzoneTownBlockHealth());
		}
		
		/*
		 * This should probably not happen because of the homeblock test above.
		 */
		if (numTownBlocks < 1) {
			TownyMessaging.sendErrorMsg("The town " + town.getName() + " does not have any land to fight over. They will not take part in the war.");
			return false;
		}	

		TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_war_join", town.getName()));
		TownyMessaging.sendPrefixedTownMessage(town, "You have joined a war of type: " + warType.getName());
		
		warringResidents.addAll(town.getResidents());
		
		/*
		 * Give the players their lives.
		 * TODO: Make mayors/kings have the ability to receive a different amount.
		 */
		for (Resident resident : town.getResidents()) 
			residentLives.put(resident, warType.lives);

		return true;
	}

	//////////////////////////////////////////////////////////////////// End of "Getters and Setters"
	
	/*
	 * WarZone Updating / Healing / Attacking / Fireworks feedback.
	 */

	/**
	 * Update a plot given the WarZoneData on the TownBlock
	 * @param townBlock - {@link TownBlock}
	 * @param wzd - {@link WarZoneData}
	 * @throws NotRegisteredException - Generic
	 */
	public void updateWarZone (TownBlock townBlock, WarZoneData wzd) throws NotRegisteredException {
		if (!wzd.hasAttackers()) 
			healPlot(townBlock, wzd);
		else
			attackPlot(townBlock, wzd);
	}

	/**
	 * Heals a plot. Only occurs when the plot has no attackers.
	 * @param townBlock - The {@link TownBlock} to be healed.
	 * @param wzd - {@link WarZoneData}
	 * @throws NotRegisteredException - Generic
	 */
	private void healPlot(TownBlock townBlock, WarZoneData wzd) throws NotRegisteredException {
		WorldCoord worldCoord = townBlock.getWorldCoord();
		int healthChange = wzd.getHealthChange();
		int oldHP = warZone.get(worldCoord);
		int hp = getHealth(townBlock, healthChange);
		if (oldHP == hp)
			return;
		warZone.put(worldCoord, hp);
		String healString =  Colors.Gray + "[Heal](" + townBlock.getCoord().toString() + ") HP: " + hp + " (" + Colors.LightGreen + "+" + healthChange + Colors.Gray + ")";
		TownyMessaging.sendPrefixedTownMessage(townBlock.getTown(), healString);
		for (Player p : wzd.getDefenders()) {
			Resident res = TownyUniverse.getInstance().getResident(p.getUniqueId());
			if (res != null && res.hasTown() && res.getTown().equals(townBlock.getTown()))
				TownyMessaging.sendMessage(p, healString);
		}
		launchFireworkAtPlot (townBlock, wzd.getRandomDefender(), Type.BALL, Color.LIME);

		//Call PlotAttackedEvent to update scoreboard users
		PlotAttackedEvent event = new PlotAttackedEvent(townBlock, wzd.getAllPlayers(), hp);
		Bukkit.getServer().getPluginManager().callEvent(event);
	}

	/**
	 * There are attackers on the plot, update the health.
	 * @param townBlock - The {@link TownBlock} being attacked
	 * @param wzd - {@link WarZoneData}
	 * @throws NotRegisteredException - Generic
	 */
	private void attackPlot(TownBlock townBlock, WarZoneData wzd) throws NotRegisteredException {

		Player attackerPlayer = wzd.getRandomAttacker();
		Resident attackerResident = com.palmergames.bukkit.towny.TownyUniverse.getInstance().getResident(attackerPlayer.getUniqueId());
		
		if (attackerResident == null)
			throw new NotRegisteredException(Translation.of("msg_err_not_registered_1", attackerPlayer.getName()));
		
		Town attacker = attackerResident.getTown();

		//Health, messaging, fireworks..
		WorldCoord worldCoord = townBlock.getWorldCoord();
		int healthChange = wzd.getHealthChange();
		int hp = getHealth(townBlock, healthChange);
		Color fwc = healthChange < 0 ? Color.RED : (healthChange > 0 ? Color.LIME : Color.GRAY);
		if (hp > 0) {
			warZone.put(worldCoord, hp);
			String healthChangeStringDef, healthChangeStringAtk;
			if (healthChange > 0) { 
				healthChangeStringDef = "(" + Colors.LightGreen + "+" + healthChange + Colors.Gray + ")";
				healthChangeStringAtk = "(" + Colors.Red + "+" + healthChange + Colors.Gray + ")";
			}
			else if (healthChange < 0) {
				healthChangeStringDef = "(" + Colors.Red + healthChange + Colors.Gray + ")";
				healthChangeStringAtk = "(" + Colors.LightGreen + healthChange + Colors.Gray + ")";
			}
			else {
				healthChangeStringDef = "(+0)";
				healthChangeStringAtk = "(+0)";
			}
			if (!townBlock.isHomeBlock()){
				TownyMessaging.sendPrefixedTownMessage(townBlock.getTown(), Colors.Gray + Translation.of("msg_war_town_under_attack") + " (" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
				if ((hp >= 10 && hp % 10 == 0) || hp <= 5){
					launchFireworkAtPlot (townBlock, attackerPlayer, Type.BALL_LARGE, fwc);
					for (Town town: townBlock.getTown().getNation().getTowns())
						if (town != townBlock.getTown())
							TownyMessaging.sendPrefixedTownMessage(town, Colors.Gray + Translation.of("msg_war_nation_under_attack") + " [" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
					for (Nation nation: townBlock.getTown().getNation().getAllies())
						if (nation != townBlock.getTown().getNation())
							TownyMessaging.sendPrefixedNationMessage(nation , Colors.Gray + Translation.of("msg_war_nations_ally_under_attack", townBlock.getTown().getName()) + " [" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
				}
				else
					launchFireworkAtPlot (townBlock, attackerPlayer, Type.BALL, fwc);
				for (Town attackingTown : wzd.getAttackerTowns())
					TownyMessaging.sendPrefixedTownMessage(attackingTown, Colors.Gray + "[" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringAtk);
			} else {
				TownyMessaging.sendPrefixedTownMessage(townBlock.getTown(), Colors.Gray + Translation.of("msg_war_homeblock_under_attack")+" (" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
				if ((hp >= 10 && hp % 10 == 0) || hp <= 5){
					launchFireworkAtPlot (townBlock, attackerPlayer, Type.BALL_LARGE, fwc);
					for (Town town: townBlock.getTown().getNation().getTowns())
						if (town != townBlock.getTown())
							TownyMessaging.sendPrefixedTownMessage(town, Colors.Gray + Translation.of("msg_war_nation_member_homeblock_under_attack", townBlock.getTown().getName()) + " [" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
					for (Nation nation: townBlock.getTown().getNation().getAllies())
						if (nation != townBlock.getTown().getNation())
							TownyMessaging.sendPrefixedNationMessage(nation , Colors.Gray + Translation.of("msg_war_nation_ally_homeblock_under_attack", townBlock.getTown().getName()) + " [" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringDef);
				}
				else
					launchFireworkAtPlot (townBlock, attackerPlayer, Type.BALL, fwc);
				for (Town attackingTown : wzd.getAttackerTowns())
					TownyMessaging.sendPrefixedTownMessage(attackingTown, Colors.Gray + "[" + townBlock.getTown().getName() + "](" + townBlock.getCoord().toString() + ") HP: " + hp + " " + healthChangeStringAtk);
			}
		} else {
			launchFireworkAtPlot (townBlock, attackerPlayer, Type.CREEPER, fwc);
			// If there's more than one Town involved we want to award it to the town with the most players present.
			if (wzd.getAttackerTowns().size() > 1) {
				Hashtable<Town, Integer> attackerCount = new Hashtable<Town, Integer>();
				for (Town town : wzd.getAttackerTowns()) {
					for (Player player : wzd.getAttackers()) {
						Resident playerRes = TownyUniverse.getInstance().getResident(player.getUniqueId());
						if (playerRes != null && playerRes.hasTown() && town.hasResident(playerRes)) {
							int i = 0;
							if (attackerCount.contains(town))
								i = attackerCount.get(town);
							attackerCount.put(town, i + 1);
						}
					}
				}
				KeyValueTable<Town, Integer> kvTable = new KeyValueTable<>(attackerCount);
				kvTable.sortByValue();
				kvTable.reverse();
				attacker = kvTable.getKeyValues().get(0).key;
			}
			remove(attacker, townBlock);
		}

		//Call PlotAttackedEvent to update scoreboard users
		PlotAttackedEvent event = new PlotAttackedEvent(townBlock, wzd.getAllPlayers(), hp);
		Bukkit.getServer().getPluginManager().callEvent(event);
	}

	/**
	 * Correctly returns the health of a {@link TownBlock} given the change in the health.
	 * 
	 * @param townBlock - The TownBlock to get health of
	 * @param healthChange - Modifier to the health of the TownBlock ({@link Integer})
	 * @return the health of the TownBlock
	 */
	private int getHealth(TownBlock townBlock, int healthChange) {
		WorldCoord worldCoord = townBlock.getWorldCoord();
		int hp = warZone.get(worldCoord) + healthChange;
		boolean isHomeBlock = townBlock.isHomeBlock();
		if (isHomeBlock && hp > TownySettings.getWarzoneHomeBlockHealth())
			return TownySettings.getWarzoneHomeBlockHealth();
		else if (!isHomeBlock && hp > TownySettings.getWarzoneTownBlockHealth())
			return TownySettings.getWarzoneTownBlockHealth();
		return hp;
	}

	/**
	 * Launch a {@link Firework} at a given plot
	 * @param townblock - The {@link TownBlock} to fire in
	 * @param atPlayer - The {@link Player} in which the location is grabbed
	 * @param type - The {@link FireworkEffect} type
	 * @param c - The Firework {@link Color}
	 */
	private void launchFireworkAtPlot(final TownBlock townblock, final Player atPlayer, final FireworkEffect.Type type, final Color c)
	{
		// Check the config. If false, do not launch a firework.
		if (!TownySettings.getPlotsFireworkOnAttacked()) {
			return;
		}
		
		BukkitTools.scheduleSyncDelayedTask(() -> {
			double x = (double)townblock.getX() * Coord.getCellSize() + Coord.getCellSize()/2.0;
			double z = (double)townblock.getZ() * Coord.getCellSize() + Coord.getCellSize()/2.0;
			double y = atPlayer.getLocation().getY() + 20;
			Firework firework = atPlayer.getWorld().spawn(new Location(atPlayer.getWorld(), x, y, z), Firework.class);
			FireworkMeta data = firework.getFireworkMeta();
			data.addEffects(FireworkEffect.builder().withColor(c).with(type).trail(false).build());
			firework.setFireworkMeta(data);
			firework.detonate();
		}, 0);
	}

	/*
	 * Removal Section
	 */
	
	/**
	 * Removes a TownBlock attacked by a Town.
	 * @param attacker attackPlot method attackerResident.getTown().
	 * @param townBlock townBlock being attacked.
	 * @throws NotRegisteredException - When a Towny Object does not exist.
	 */
	private void remove(Town attacker, TownBlock townBlock) throws NotRegisteredException {
		// Add bonus blocks
		Town defenderTown = townBlock.getTown();
		boolean defenderHomeblock = townBlock.isHomeBlock();
		if (TownySettings.getWarEventCostsTownblocks() || TownySettings.getWarEventWinnerTakesOwnershipOfTownblocks()){		
			defenderTown.addBonusBlocks(-1);
			attacker.addBonusBlocks(1);
		}
		
		// We only change the townblocks over to the winning Town if the WinnerTakesOwnershipOfTown is false and WinnerTakesOwnershipOfTownblocks is true.
		if (!TownySettings.getWarEventWinnerTakesOwnershipOfTown() && TownySettings.getWarEventWinnerTakesOwnershipOfTownblocks()) {
			townBlock.setTown(attacker);
			townBlock.save();
		}		
		
		TownyUniverse townyUniverse = TownyUniverse.getInstance();
		try {
			// Check for money loss in the defending town
			if (TownyEconomyHandler.isActive() && !defenderTown.getAccount().payTo(TownySettings.getWartimeTownBlockLossPrice(), attacker, "War - TownBlock Loss")) {
				TownyMessaging.sendPrefixedTownMessage(defenderTown, Translation.of("msg_war_town_ran_out_of_money"));
				TownyMessaging.sendTitleMessageToTown(defenderTown, Translation.of("msg_war_town_removed_from_war_titlemsg"), "");
				if (defenderTown.isCapital())
					remove(attacker, defenderTown.getNation());
				else
					remove(attacker, defenderTown);
				defenderTown.save();
				attacker.save();
				return;
			} else
				TownyMessaging.sendPrefixedTownMessage(defenderTown, Translation.of("msg_war_town_lost_money_townblock", TownyEconomyHandler.getFormattedBalance(TownySettings.getWartimeTownBlockLossPrice())));
		} catch (EconomyException ignored) {}
		
		// Check to see if this is a special TownBlock
		if (defenderHomeblock && defenderTown.isCapital()){
			remove(attacker, defenderTown.getNation());
		} else if (defenderHomeblock){
			remove(attacker, defenderTown);
		} else{
			townScored(attacker, TownySettings.getWarPointsForTownBlock(), townBlock, 0);
			remove(townBlock.getWorldCoord());
			// Free players who are jailed in the jail plot.
			if (townBlock.getType().equals(TownBlockType.JAIL)){
				int count = 0;
				for (Resident resident : townyUniverse.getJailedResidentMap()){
					try {						
						if (resident.isJailed())
							if (resident.getJailTown().equals(defenderTown.toString())) 
								if (Coord.parseCoord(defenderTown.getJailSpawn(resident.getJailSpawn())).toString().equals(townBlock.getCoord().toString())){
									resident.setJailed(false);
									resident.save();
									count++;
								}
					} catch (TownyException e) {
					}
				}
				if (count>0)
					TownyMessaging.sendGlobalMessage(Translation.of("msg_war_jailbreak", defenderTown, count));
			}				
		}
		defenderTown.save();
		attacker.save();
	}

	/** 
	 * Removes a Nation from the war, attacked by a Town. 
	 * @param attacker Town which attacked the Nation.
	 * @param nation Nation being removed from the war.
	 * @throws NotRegisteredException - When a Towny Object does not exist.
	 */
	public void remove(Town attacker, Nation nation) throws NotRegisteredException {

		townScored(attacker, TownySettings.getWarPointsForNation(), nation, 0);
		warringNations.remove(nation);
		TownyMessaging.sendGlobalMessage(Translation.of("msg_war_eliminated", nation));
		for (Town town : nation.getTowns())
			if (warringTowns.contains(town))
				remove(attacker, town);
		checkEnd();
	}

	/**
	 * Removes a Town from the war, attacked by a Town.
	 * @param attacker Town which attacked.
	 * @param town Town which is being removed from the war.
	 * @throws NotRegisteredException - When a Towny Object does not exist.
	 */
	public void remove(Town attacker, Town town) throws NotRegisteredException {
		Nation losingNation = town.getNation();
		
		int towns = 0;
		for (Town townsToCheck : warringTowns) {
			if (townsToCheck.getNation().equals(losingNation))
				towns++;
		}

		int fallenTownBlocks = 0;
		warringTowns.remove(town);
		for (TownBlock townBlock : town.getTownBlocks())
			if (warZone.containsKey(townBlock.getWorldCoord())){
				fallenTownBlocks++;
				remove(townBlock.getWorldCoord());
			}
		townScored(attacker, TownySettings.getWarPointsForTown(), town, fallenTownBlocks);
		
		if (TownySettings.getWarEventWinnerTakesOwnershipOfTown()) {			
			town.setConquered(true);
			town.setConqueredDays(TownySettings.getWarEventConquerTime());

			// if losingNation is not a one-town nation then this.
			town.removeNation();
			try {
				town.setNation(attacker.getNation());
			} catch (AlreadyRegisteredException e) {
			}
			town.save();
			attacker.getNation().save();
			losingNation.save();
			TownyMessaging.sendGlobalMessage(Translation.of("msg_war_town_has_been_conquered_by_nation_x_for_x_days", town.getName(), attacker.getNation(), TownySettings.getWarEventConquerTime()));
		}
		
		if (towns == 1)
			remove(losingNation);
		checkEnd();
	}
	
	/**
	 * Removes a Nation from the war.
	 * Called when a Nation voluntarily leaves a war.
	 * Called by remove(Town town). 
	 * @param nation Nation being removed from the war.
	 */
	private void remove(Nation nation) {

		warringNations.remove(nation);
		sendEliminateMessage(nation.getFormattedName());
		TownyMessaging.sendTitleMessageToNation(nation, Translation.of("msg_war_nation_removed_from_war_titlemsg"), "");
		for (Town town : nation.getTowns())
			remove(town);
		checkEnd();
	}

	/**
	 * Removes a Town from the war.
	 * Called when a player is killed and their Town Bank cannot pay the war penalty.
	 * Called when a Town voluntarily leaves a War.
	 * Called by remove(Nation nation).
	 * @param town The Town being removed from the war.
	 */
	public void remove(Town town) {

		// If a town is removed, is a capital, and the nation has not been removed, call remove(nation) instead.
		try {
			if (town.isCapital() && warringNations.contains(town.getNation())) {
				remove(town.getNation());
				return;
			}
		} catch (NotRegisteredException e) {}
		
		int fallenTownBlocks = 0;
		warringTowns.remove(town);
		for (TownBlock townBlock : town.getTownBlocks())
			if (warZone.containsKey(townBlock.getWorldCoord())){
				fallenTownBlocks++;
				remove(townBlock.getWorldCoord());
			}
		for (Resident resident : town.getResidents()) {
			if (warringResidents.contains(resident))
				remove(resident);
		}
		town.setActiveWar(false);
		sendEliminateMessage(town.getFormattedName() + " (" + fallenTownBlocks + Translation.of("msg_war_append_townblocks_fallen"));
	}
	
	/**
	 * Removes a resident from the war.
	 * 
	 * Called by takeLife(Resident resident)
	 * Called by remove(Town town)
	 * @param resident
	 */
	public void remove(Resident resident) {
		warringResidents.remove(resident);
	}

	/**
	 * Removes one WorldCoord from the warZone hashtable.
	 * @param worldCoord WorldCoord being removed from the war.
	 */
	private void remove(WorldCoord worldCoord) {	
		warZone.remove(worldCoord);
	}

	private void sendEliminateMessage(String name) {
		TownyMessaging.sendGlobalMessage(Translation.of("msg_war_eliminated", name));
	}

	/*
	 * Voluntary leaving section. (UNUSED AS OF YET)
	 * 
	 * TODO: set up some leave commands because these are unused!
	 */

	@Deprecated
	public void nationLeave(Nation nation) {

		remove(nation);
		TownyMessaging.sendGlobalMessage(Translation.of("MSG_WAR_FORFEITED", nation.getName()));
		checkEnd();
	}

	@Deprecated
	public void townLeave(Town town) {

		remove(town);
		TownyMessaging.sendGlobalMessage(Translation.of("MSG_WAR_FORFEITED", town.getName()));
		checkEnd();
	}

	/*
	 * Stats
	 */

	public List<String> getStats() {

		List<String> output = new ArrayList<>();
		output.add(ChatTools.formatTitle("War Stats"));
		
		switch (warType) {
			case WORLDWAR:
			case NATIONWAR:
				output.add(Colors.Green + Translation.of("war_stats_nations") + Colors.LightGreen + warringNations.size() + " / " + totalNationsAtStart);
			case CIVILWAR:
			case TOWNWAR:
				output.add(Colors.Green + Translation.of("war_stats_towns") + Colors.LightGreen + warringTowns.size() + " / " + townScores.size());
			case RIOT:
				output.add(Colors.Green + "  Residents: " + Colors.LightGreen + warringResidents.size() + " / " + totalResidentsAtStart);
				break;
		}		
		if (warType.hasTownBlockHP)
			output.add(Colors.Green + Translation.of("war_stats_warzone") + Colors.LightGreen + warZone.size() + " Town blocks");
		try {
			output.add(Colors.Green + Translation.of("war_stats_spoils_of_war") + Colors.LightGreen + TownyEconomyHandler.getFormattedBalance(warSpoils.getHoldingBalance()));
			return output;
		} catch (EconomyException e) {
		}
		return null;
	}
	
	public void sendStats(Player player) {

		for (String line : getStats())
			player.sendMessage(line);
	}

	/*
	 * Scoring Methods
	 */
	
	/**
	 * A resident has killed another resident.
	 * @param defender - {@link Resident} dying.
	 * @param attacker - {@link Resident} killing.
	 * @param loc - {@link Location} of the death.
	 */
	public void residentScoredKillPoints(Resident defender,  Resident attacker, Location loc) {
		switch(warType) {
		case RIOT:
			// TODO: Handle riot scoring.
			break;
		default:
			townScored(defender, attacker, loc);			
			break;
		}
	}

	/**
	 * A town has scored a kill point.
	 * 
	 * @param defender - {@link Resident} dying.
	 * @param attackerRes - {@link Resident} killing.
	 * @param loc - {@link Location} of the death.
	 */
	private void townScored(Resident defender, Resident attacker, Location loc) {
		int points = TownySettings.getWarPointsForKill();
		Town attackerTown = null;
		Town defenderTown = null;
		try {
			attackerTown = attacker.getTown();
			defenderTown = defender.getTown();
		} catch (NotRegisteredException ignored) {}
		
		String pointMessage;
		TownBlock deathLoc = TownyAPI.getInstance().getTownBlock(loc);
		if (deathLoc == null)
			pointMessage = Translation.of("MSG_WAR_SCORE_PLAYER_KILL", attacker.getName(), defender.getName(), points, attackerTown.getName());
		else if (warZone.containsKey(deathLoc.getWorldCoord()) && attackerTown.getTownBlocks().contains(deathLoc))
			pointMessage = Translation.of("MSG_WAR_SCORE_PLAYER_KILL_DEFENDING", attacker.getName(), defender.getName(), attacker.getName(), points, attackerTown.getName());
		else if (warZone.containsKey(deathLoc.getWorldCoord()) && defenderTown.getTownBlocks().contains(deathLoc))
			pointMessage = Translation.of("MSG_WAR_SCORE_PLAYER_KILL_DEFENDING", attacker.getName(), defender.getName(), defender.getName(), points, attackerTown.getName());
		else
			pointMessage = Translation.of("MSG_WAR_SCORE_PLAYER_KILL", attacker.getName(), defender.getName(), points, attackerTown.getName());

		townScores.put(attackerTown, townScores.get(attackerTown) + points);
		TownyMessaging.sendGlobalMessage(pointMessage);

		TownScoredEvent event = new TownScoredEvent(attackerTown, townScores.get(attackerTown));
		Bukkit.getServer().getPluginManager().callEvent(event);
	}
	
	/**
	 * A town has scored.
	 * @param town - the scoring town
	 * @param n - the score to be added
	 * @param fallenObject - the {@link Object} that fell
	 * @param townBlocksFallen -  the number of fallen townblocks {@link TownBlock}s ({@link Integer})
	 */
	private void townScored(Town town, int n, Object fallenObject, int townBlocksFallen) {

		String pointMessage = "";
		if (fallenObject instanceof Nation)
			pointMessage = Translation.of("MSG_WAR_SCORE_NATION_ELIM", town.getName(), n, ((Nation)fallenObject).getName());
		else if (fallenObject instanceof Town)
			pointMessage = Translation.of("MSG_WAR_SCORE_TOWN_ELIM", town.getName(), n, ((Town)fallenObject).getName(), townBlocksFallen);
		else if (fallenObject instanceof TownBlock){
			String townBlockName = "";
			try {
				townBlockName = "[" + ((TownBlock)fallenObject).getTown().getName() + "](" + ((TownBlock)fallenObject).getCoord().toString() + ")";
			} catch (NotRegisteredException ignored) {}
				pointMessage = Translation.of("MSG_WAR_SCORE_TOWNBLOCK_ELIM", town.getName(), n, townBlockName);
		}

		townScores.put(town, townScores.get(town) + n);
		TownyMessaging.sendGlobalMessage(pointMessage);

		TownScoredEvent event = new TownScoredEvent(town, townScores.get(town));
		Bukkit.getServer().getPluginManager().callEvent(event);
	}
	
	/**
	 * Takes a life from the resident, removes them from the war if they have none remaining.
	 * @param resident
	 */
	public void takeLife(Resident resident) {
		if (residentLives.get(resident) == 0) {
			remove(resident);
			checkEnd();
		} else {
			residentLives.put(resident, residentLives.get(resident) - 1);
		}
	}
	
	/**
	 * Gets the scores of a {@link War}
	 * @param maxListing Maximum lines to return. Value of -1 return all.
	 * @return A list of the current scores per town sorted in descending order.
	 */
	public List<String> getScores(int maxListing) {

		List<String> output = new ArrayList<>();
		output.add(ChatTools.formatTitle("War - Top Scores"));
		KeyValueTable<Town, Integer> kvTable = new KeyValueTable<>(townScores);
		kvTable.sortByValue();
		kvTable.reverse();
		int n = 0;
		for (KeyValue<Town, Integer> kv : kvTable.getKeyValues()) {
			n++;
			if (maxListing != -1 && n > maxListing)
				break;
			Town town = kv.key;
			int score = kv.value;
			if (score > 0)
				output.add(String.format(Colors.Blue + "%40s " + Colors.Gold + "|" + Colors.LightGray + " %4d", town.getFormattedName(), score));
		}
		return output;
	}

	public String[] getTopThree() {
		KeyValueTable<Town, Integer> kvTable = new KeyValueTable<>(townScores);
		kvTable.sortByValue();
		kvTable.reverse();
		String[] top = new String[3];
		top[0] = kvTable.getKeyValues().size() >= 1 ? kvTable.getKeyValues().get(0).value + "-" + kvTable.getKeyValues().get(0).key : "";
		top[1] = kvTable.getKeyValues().size() >= 2 ? kvTable.getKeyValues().get(1).value + "-" + kvTable.getKeyValues().get(1).key : "";
		top[2] = kvTable.getKeyValues().size() >= 3 ? kvTable.getKeyValues().get(2).value + "-" + kvTable.getKeyValues().get(2).key : "";
		return top;
	}

	public KeyValue<Town, Integer> getWinningTownScore() throws TownyException {

		KeyValueTable<Town, Integer> kvTable = new KeyValueTable<>(townScores);
		kvTable.sortByValue();
		kvTable.reverse();
		if (kvTable.getKeyValues().size() > 0)
			return kvTable.getKeyValues().get(0);
		else
			throw new TownyException();
	}
	
	public void sendScores(Player player) {

		sendScores(player, 10);
	}

	public void sendScores(Player player, int maxListing) {

		for (String line : getScores(maxListing))
			player.sendMessage(line);
	}
	
	/*
	 * Private Start of War Methods.
	 */
	
	/**
	 * Picks out a name (sometimes a randomly generated one,) for the war.
	 */
	private void setWarName() {
		
		String warName = null;
		switch (warType) {
		case WORLDWAR:
			warName = String.format("World War of %s", NameGenerator.getRandomWarName());
			break;
		case NATIONWAR:			
			warName = String.format("War of %s", NameGenerator.getRandomWarName());
			break;
		case TOWNWAR:
			warName = String.format("%s - %s Skirmish", warringTowns.get(0), warringTowns.get(1));
			break;
		case CIVILWAR:
			warName = String.format("%s Civil War", warringNations.get(0));
			break;
		case RIOT:
			warName = String.format("%s Riot", warringTowns.get(0));
			break;
		}
		this.warName = warName;
	}

	/**
	 * Creates the first book given to players in the war.
	 * @return String containing the raw text of what will become a book.
	 */
	private String createWarStartBookText() {
		
		/*
		 * Flashy Header.
		 */
		String text = "oOo War Declared! oOo" + newline;
		text += "-" + warDateFormat.format(System.currentTimeMillis()) + "-" + newline;
		text += "-------------------" + newline;
		
		/*
		 * Add who is involved.
		 */
		switch(warType) {
			case WORLDWAR:
				
				text += "War has broken out across all enemied nations!" + newline;
				text += newline;
				text += "The following nations have joined the battle: " + newline;
				for (Nation nation : warringNations)
					text+= "* " + nation.getName() + newline;
				text += newline;
				text += "May the victors bring glory to their nation!";			
				break;
				
			case NATIONWAR:
				
				text += "War has broken out between two nations:" + newline;
				for (Nation nation : warringNations)
					text+= "* " + nation.getName() + newline;
				text += newline;
				text += "May the victor bring glory to their nation!";
				break;
				
			case CIVILWAR:
				
				text += String.format("Civil war has broken out in the nation of %s!", warringNations.get(0).getName()) + newline ;
				text += newline;
				text += "The following towns have joined the battle: " + newline;
				for (Town town : warringTowns)
					text+= "* " + town.getName() + newline;
				text += newline;
				text += "May the victor bring peace to their nation!";
				break;
				
			case TOWNWAR:
				
				text += "War has broken out between two towns:";
				for (Town town : warringTowns)
					text+= newline + "* " + town.getName();
				text += newline;
				text += "May the victor bring glory to their town!";
				break;
				
			case RIOT:
				
				text += String.format("A riot has broken out in the town of %s!", warringTowns.get(0).getName()) + newline;
				text += newline;
				text += "The following residents have taken up arms: " + newline;
				for (Resident resident: warringTowns.get(0).getResidents())
					text+= "* " + resident.getName() + newline;
				for (Resident resident: warringTowns.get(0).getResidents())
					text+= "* " + resident.getName() + newline;
				text += newline;
				text += "The last man standing will be the leader, but what will remain?!";
				break;
		}
		
		/*
		 * Add scoring types and winnings at stake.
		 */
		text += newline;
		text += "-------------------" + newline;
		text += "War Rules:" + newline;
		if (warType.hasTownBlockHP) {
			text += "Town blocks will have an HP stat. " + newline;
			text += "Regular Townblocks have an HP of " + TownySettings.getWarzoneTownBlockHealth() + ". ";
			text += "Homeblocks have an HP of " + TownySettings.getWarzoneHomeBlockHealth() + ". ";
			text += "Townblocks lose HP when enemies stand anywhere inside of the plot above Y level " + TownySettings.getMinWarHeight() + ". ";
			if (TownySettings.getPlotsHealableInWar())
				text += "Townblocks that have not dropped all the way to 0 hp are healable by town members and their allies. ";
			if (TownySettings.getOnlyAttackEdgesInWar())
				text += "Only edge plots will be attackable at first, so protect your borders and at all costs, do not let the enemy drop your homeblock to 0 hp! ";
			if (warType.hasTownBlocksSwitchTowns)
				text += "Townblocks which drop to 0 hp will be taken over by the attacker permanently! ";
			else
				text += "Townblocks which drop to 0 hp will not change ownership after the war. ";
			if (warType.hasTownConquering) {
				text += "Towns that have their homeblock drop to 0 hp will leave their nation and join the nation who conquered them. ";
				if (TownySettings.getWarEventConquerTime() > 0)
					text += "These towns will be conquered for " + TownySettings.getWarEventConquerTime() + " days. ";
			}
		}
		text += newline;
		if (warType.hasMonarchDeath && warType.lives > 0) {
			text += newline + "If your king or mayor runs out of lives your nation or town will be removed from the war! ";
		}
		if (warType.lives > 0)
			text += newline + "Everyone will start with " + warType.lives + (warType.lives == 1 ? " life.":" lives.") + " If you run out of lives and die again you will be removed from the war. ";
		else
			text += newline + "There are unlimited lives. ";
		text += newline;
		text += "WarSpoils up for grabs at the end of this war: " + TownyEconomyHandler.getFormattedBalance(warSpoilsAtStart);
		
		return text;
	}

	/**
	 * Verifies that for the WarType there are enough residents/towns/nations involved to have at least 2 sides.
	 * @return
	 */
	private boolean verifyTwoEnemies() {
		switch(warType) {
		case WORLDWAR:
		case NATIONWAR:
			// Cannot have a war with less than 2 nations.
			if (warringNations.size() < 2) {
				TownyMessaging.sendGlobalMessage(Translation.of("msg_war_not_enough_nations"));
				warringNations.clear();
				warringTowns.clear();
				return false;
			}
			
			// Lets make sure that at least 2 nations consider each other enemies.
			boolean enemy = false; 
			for (Nation nation : warringNations) {
				for (Nation nation2 : warringNations) {
					if (nation.hasEnemy(nation2) && nation2.hasEnemy(nation)) {
						enemy = true;
						break;
					}
				}			
			}
			if (!enemy) {
				TownyMessaging.sendGlobalMessage(Translation.of("msg_war_no_enemies_for_war"));
				return false;
			}
			break;
		case CIVILWAR:
			if (warringNations.size() > 1) {
				TownyMessaging.sendGlobalMessage("Too many nations for a civil war!");
				return false;
			}
			break;
		case TOWNWAR:
			if (warringTowns.size() < 2) {
				TownyMessaging.sendGlobalMessage("Not enough Towns for town vs town war!");
				return false;
			}
			//TODO: add town enemy checking.
			break;
		case RIOT:
			if (warringTowns.size() > 1 ) {
				TownyMessaging.sendGlobalMessage("Too many towns gathered for a riot war!");
			}
			break;
		}
		
		for (Town town : warringTowns) {
			town.setActiveWar(true);
		}
		return true;
	}

	/*
	 * Private End of War Methods
	 */
	
	/**
	 * Remove all the war huds.
	 * @param war
	 */
	private void removeWarHuds(War war) {
		new BukkitRunnable() {

			@Override
			public void run() {
				plugin.getHUDManager().toggleAllWarHUD(war);
			}
			
		}.runTask(plugin);		
	}
	
	/**
	 * Pay out the money to the winner(s).
	 */
	private void awardSpoils() {
		double halfWinnings;
		double nationWinnings = 0;
		try {
			
			// Compute war spoils
			halfWinnings = getWarSpoils().getHoldingBalance() / 2.0;
			try {
				nationWinnings = halfWinnings / warringNations.size(); // Again, might leave residue.
				for (Nation winningNation : warringNations) {
					getWarSpoils().payTo(nationWinnings, winningNation, "War - Nation Winnings");
					TownyMessaging.sendGlobalMessage(Translation.of("MSG_WAR_WINNING_NATION_SPOILS", winningNation.getName(), TownyEconomyHandler.getFormattedBalance(nationWinnings)));
				}
			} catch (ArithmeticException e) {
				TownyMessaging.sendDebugMsg("[War]   War ended with 0 nations.");
			}

			// Pay money to winning town and print message
			try {
				KeyValue<Town, Integer> winningTownScore = getWinningTownScore();
				getWarSpoils().payTo(halfWinnings, winningTownScore.key, "War - Town Winnings");
				TownyMessaging.sendGlobalMessage(Translation.of("MSG_WAR_WINNING_TOWN_SPOILS", winningTownScore.key.getName(), TownyEconomyHandler.getFormattedBalance(halfWinnings),  winningTownScore.value));
				
				EventWarEndEvent event = new EventWarEndEvent(warringTowns, winningTownScore.key, halfWinnings, warringNations, nationWinnings);
				Bukkit.getServer().getPluginManager().callEvent(event);
			} catch (TownyException e) {
			}
		} catch (EconomyException e1) {}
	}

	/*
	 * Unused counting methods. 
	 */

	@Deprecated
	public int countActiveWarBlocks(Town town) {

		int n = 0;
		for (TownBlock townBlock : town.getTownBlocks())
			if (warZone.containsKey(townBlock.getWorldCoord()))
				n++;
		return n;
	}
	
	@Deprecated
	public int countActiveTowns(Nation nation) {

		int n = 0;
		for (Town town : nation.getTowns())
			if (warringTowns.contains(town))
				n++;
		return n;
	}

	@Deprecated
	public boolean townsLeft(Nation nation) {

		return countActiveTowns(nation) > 0;
	}
	

}