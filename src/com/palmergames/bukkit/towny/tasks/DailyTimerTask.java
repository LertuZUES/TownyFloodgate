package com.palmergames.bukkit.towny.tasks;

import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyMessaging;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.NewDayEvent;
import com.palmergames.bukkit.towny.event.PreNewDayEvent;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.object.Translation;
import com.palmergames.bukkit.towny.permissions.TownyPerms;
import com.palmergames.bukkit.towny.utils.TownPeacefulnessUtil;
import com.palmergames.bukkit.towny.utils.MoneyUtil;
import com.palmergames.bukkit.util.ChatTools;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

public class DailyTimerTask extends TownyTimerTask {
	
	private double totalTownUpkeep = 0.0;
	private double totalNationUpkeep = 0.0;
	private final List<String> bankruptedTowns = new ArrayList<>();
	private final List<String> removedTowns = new ArrayList<>();
	private final List<String> removedNations = new ArrayList<>();

	public DailyTimerTask(Towny plugin) {

		super(plugin);
	}

	@Override
	public void run() {

		long start = System.currentTimeMillis();
		totalTownUpkeep = 0.0;
		totalNationUpkeep = 0.0;
		bankruptedTowns.clear();
		removedTowns.clear();
		removedNations.clear();

		Bukkit.getPluginManager().callEvent(new PreNewDayEvent()); // Pre-New Day Event
		
		TownyMessaging.sendDebugMsg("New Day");

		/*
		 * If enabled, collect taxes and then server upkeep costs.
		 */		
		if (TownyEconomyHandler.isActive() && TownySettings.isTaxingDaily()) {
			TownyMessaging.sendGlobalMessage(Translation.of("msg_new_day_tax"));
			try {
				TownyMessaging.sendDebugMsg("Collecting Town Taxes");
				collectTownTaxes();
				TownyMessaging.sendDebugMsg("Collecting Nation Taxes");
				collectNationTaxes();
				TownyMessaging.sendDebugMsg("Collecting Town Costs");
				collectTownCosts();
				TownyMessaging.sendDebugMsg("Collecting Nation Costs");
				collectNationCosts();
				
				Bukkit.getServer().getPluginManager().callEvent(new NewDayEvent(bankruptedTowns, removedTowns, removedNations, totalTownUpkeep, totalNationUpkeep, start));
				
			} catch (EconomyException ex) {
				TownyMessaging.sendErrorMsg("Economy Exception");
				ex.printStackTrace();
			} catch (TownyException e) {
				// TODO king exception
				e.printStackTrace();
			}
		} else
			TownyMessaging.sendGlobalMessage(Translation.of("msg_new_day"));

		/*
		 * If enabled, remove old residents who haven't logged in for the configured number of days.
		 */	
		if (TownySettings.isDeletingOldResidents()) {
			// Run a purge in it's own thread
			new ResidentPurge(plugin, null, TownySettings.getDeleteTime() * 1000, TownySettings.isDeleteTownlessOnly()).start();
		}

		/*
		 * If enabled, remove all 0-plot towns.
		 */
		if (TownySettings.isNewDayDeleting0PlotTowns()) {
			List<String> deletedTowns = new ArrayList<>();
			for (Town town : universe.getTownsMap().values()) {
				if (town.getTownBlocks().size() == 0) {
					deletedTowns.add(town.getName());
					universe.getDataSource().removeTown(town);
				}
			}
			if (!deletedTowns.isEmpty())
				TownyMessaging.sendGlobalMessage(Translation.of("msg_the_following_towns_were_deleted_for_having_0_claims", String.join(", ", deletedTowns)));
		}
		
		/*
		 * Reduce the number of days jailed residents are jailed for.
		 */
		if (!universe.getJailedResidentMap().isEmpty()) {
			for (Resident resident : universe.getJailedResidentMap()) {
				if (resident.hasJailDays()) {
					if (resident.getJailDays() == 1) {
						resident.setJailDays(0);
						new BukkitRunnable() {

				            @Override
				            public void run() {				            	
				            	Town jailTown = null;
								try {
									jailTown = universe.getDataSource().getTown(resident.getJailTown());
								} catch (NotRegisteredException ignored) {
								}
								int index = resident.getJailSpawn();
				            	resident.setJailed(index, jailTown);
				            }
				            
				        }.runTaskLater(this.plugin, 20);
					} else 
						resident.setJailDays(resident.getJailDays() - 1);
					
				}
				universe.getDataSource().saveResident(resident);
			}			
		}
		
		/*
		 * Reduce the number of days conquered towns are conquered for.
		 */
		for (Town towns : universe.getDataSource().getTowns()) {
			if (towns.isConquered()) {
				if (towns.getConqueredDays() == 1) {
					towns.setConquered(false);
					towns.setConqueredDays(0);
				} else
					towns.setConqueredDays(towns.getConqueredDays() - 1);				
			}
		}

		/*
		 * Update town peacefulness counters.
		 */
		if (TownySettings.getWarCommonPeacefulTownsEnabled()) {
			TownPeacefulnessUtil.updateTownPeacefulnessCounters();
			if(TownySettings.getWarSiegeEnabled())
				TownPeacefulnessUtil.evaluatePeacefulTownNationAssignments();
		}

		/*
		 * Run backup on a separate thread, to let the DailyTimerTask thread terminate as intended.
		 */
		if (TownySettings.isBackingUpDaily()) {			
			universe.performCleanupAndBackup();
		}

		TownyMessaging.sendDebugMsg("Finished New Day Code");
		TownyMessaging.sendDebugMsg("Universe Stats:");
		TownyMessaging.sendDebugMsg("    Residents: " + universe.getDataSource().getResidents().size());
		TownyMessaging.sendDebugMsg("    Towns: " + universe.getDataSource().getTowns().size());
		TownyMessaging.sendDebugMsg("    Nations: " + universe.getDataSource().getNations().size());
		for (TownyWorld world : universe.getDataSource().getWorlds())
			TownyMessaging.sendDebugMsg("    " + world.getName() + " (townblocks): " + universe.getTownBlocks().size());

		TownyMessaging.sendDebugMsg("Memory (Java Heap):");
		TownyMessaging.sendDebugMsg(String.format("%8d Mb (max)", Runtime.getRuntime().maxMemory() / 1024 / 1024));
		TownyMessaging.sendDebugMsg(String.format("%8d Mb (total)", Runtime.getRuntime().totalMemory() / 1024 / 1024));
		TownyMessaging.sendDebugMsg(String.format("%8d Mb (free)", Runtime.getRuntime().freeMemory() / 1024 / 1024));
		TownyMessaging.sendDebugMsg(String.format("%8d Mb (used=total-free)", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024));
		Towny.getPlugin().getLogger().info("Towny DailyTimerTask took " + (System.currentTimeMillis() - start) + "ms to process.");
	}

	/**
	 * Collect taxes for all nations due from their member towns
	 * 
	 * @throws EconomyException - EconomyException
	 */
	public void collectNationTaxes() throws EconomyException {
		List<Nation> nations = new ArrayList<>(universe.getDataSource().getNations());
		ListIterator<Nation> nationItr = nations.listIterator();
		Nation nation;

		while (nationItr.hasNext()) {
			nation = nationItr.next();
			/*
			 * Only collect tax for this nation if it really still exists.
			 * We are running in an Async thread so MUST verify all objects.
			 */
			if (universe.getDataSource().hasNation(nation.getName()))
				collectNationTaxes(nation);
		}
	}

	/**
	 * Collect taxes due to the nation from it's member towns.
	 * 
	 * @param nation - Nation to collect taxes from.
	 * @throws EconomyException - EconomyException
	 */
	protected void collectNationTaxes(Nation nation) throws EconomyException {
		
		if (nation.getTaxes() > 0) {

			List<String> localTownsDestroyed = new ArrayList<>();
			double taxAmount = nation.getTaxes();
			List<String> localNewlyDelinquentTowns = new ArrayList<>();
			List<Town> towns = new ArrayList<>(nation.getTowns());
			ListIterator<Town> townItr = towns.listIterator();
			Town town;

			while (townItr.hasNext()) {
				town = townItr.next();

				/*
				 * Only collect nation tax from this town if 
				 * - It exists
				 * - It is not the capital
				 * - It is not ruined
				 * - It is not neutral
				 * 
				 * We are running in an Async thread so MUST verify all objects.
				 */
				if (universe.getDataSource().hasTown(town.getName())) {
					if (town.isCapital() || !town.hasUpkeep() || town.isRuined() || (TownySettings.getWarSiegeEnabled()
							&& TownySettings.getWarCommonPeacefulTownsEnabled() && town.isPeaceful()))
						continue;

					if (town.getAccount().canPayFromHoldings(taxAmount)) {
					// Town is able to pay the nation's tax.
						town.getAccount().payTo(taxAmount, nation, "Nation Tax to " + nation.getName());
						TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_payed_nation_tax", TownyEconomyHandler.getFormattedBalance(taxAmount)));
					} else {
					// Town is unable to pay the nation's tax.
						if (!TownySettings.isTownBankruptcyEnabled() || !TownySettings.doBankruptTownsPayNationTax()) {
						
							/*
							 * TODO: RECHECK THIS IS WORKING. 
							 */
							//If town is occupied, destroy it, otherwise remove from nation
							if (TownySettings.getWarSiegeEnabled() && town.isOccupied()) {
								universe.getDataSource().removeTown(town);
								localTownsDestroyed.add(town.getName());
							}
							/*
							 * TODO: This is part of the messy business with getting bankruptcy in SW and Towny to work together again.
							 * Towny's dailytimertask got a lot of work done on it.
							 */
							

						// Bankruptcy disabled, remove town for not paying nation tax, 
						// OR Bankruptcy enabled but towns aren't allowed to use debt to pay nation tax. 
							localNewlyDelinquentTowns.add(town.getName());		
							town.removeNation();
							TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_couldnt_pay_the_nation_tax_of", TownyEconomyHandler.getFormattedBalance(taxAmount)));
							continue;
						}

						// Bankruptcy enabled and towns are allowed to use debt to pay nation tax.
						boolean townWasBankrupt = town.isBankrupt();
						town.getAccount().setDebtCap(MoneyUtil.getEstimatedValueOfTown(town));
						
						if (town.getAccount().getHoldingBalance() - taxAmount < town.getAccount().getDebtCap() * -1) {
						// Towns that would go over their debtcap to pay nation tax, need the amount they pay reduced to what their debt cap can cover.
						// This will result in towns that become fully indebted paying 0 nation tax eventually.

							if (TownySettings.isNationTaxKickingTownsThatReachDebtCap()) {
							// Alternatively, when configured, a nation will kick a town that  
							// can no longer pay the full nation tax with their allowed debt. 
								localNewlyDelinquentTowns.add(town.getName());		
								town.removeNation();
								TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_couldnt_pay_the_nation_tax_of", TownyEconomyHandler.getFormattedBalance(nation.getTaxes())));
								continue;
							}
							
							taxAmount = town.getAccount().getDebtCap() - Math.abs(town.getAccount().getHoldingBalance());
						}

						// Pay the nation tax with at least some amount of debt.
						town.getAccount().withdraw(taxAmount, "Nation Tax to " + nation.getName()); // .withdraw() is used because other economy methods do not allow a town to go into debt.
						nation.getAccount().deposit(taxAmount, "Nation Tax from " + town.getName());
						TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_payed_nation_tax", TownyEconomyHandler.getFormattedBalance(taxAmount)));

						// Check if the town was newly bankrupted and punish them for it.
						if (!townWasBankrupt && town.isBankrupt()) {
							town.setOpen(false);
							universe.getDataSource().saveTown(town);
							localNewlyDelinquentTowns.add(town.getName());
						}
					}
				}
			}

			String msg1 = "msg_couldnt_pay_tax";
			String msg2 = "msg_couldnt_pay_nation_tax_multiple";
			if (TownySettings.isTownBankruptcyEnabled() && TownySettings.doBankruptTownsPayNationTax()) { 
				msg1 = "msg_town_bankrupt_by_nation_tax";
				msg2 = "msg_town_bankrupt_by_nation_tax_multiple";
			}
			
			if (localNewlyDelinquentTowns.size() == 1)
				TownyMessaging.sendPrefixedNationMessage(nation, Translation.of(msg1, localNewlyDelinquentTowns.get(0), Translation.of("nation_sing")));
			else
				TownyMessaging.sendPrefixedNationMessage(nation, ChatTools.list(localNewlyDelinquentTowns, msg2));
			
			if(TownySettings.getWarSiegeEnabled()) {
				if (localTownsDestroyed.size() > 0) {
					if (localTownsDestroyed.size() == 1)
						TownyMessaging.sendNationMessagePrefixed(nation, Translation.of("msg_town_destroyed_by_nation_tax", ChatTools.list(localTownsDestroyed)));
					else
						TownyMessaging.sendNationMessagePrefixed(nation, ChatTools.list(localTownsDestroyed, Translation.of("msg_town_destroyed_by_nation_tax_multiple")));
				}
			}
		}
	}

	/**
	 * Collect taxes for all towns due from their residents.
	 * 
	 * @throws EconomyException - EconomyException
	 */
	public void collectTownTaxes() throws EconomyException {
		List<Town> towns = new ArrayList<>(universe.getDataSource().getTowns());
		ListIterator<Town> townItr = towns.listIterator();
		Town town;

		while (townItr.hasNext()) {
			town = townItr.next();
			/*
			 * Only collect resident tax for this town if it really still
			 * exists.
			 * We are running in an Async thread so MUST verify all objects.
			 */

			if (universe.getDataSource().hasTown(town.getName()) && !town.isRuined())
				collectTownTaxes(town);
		}
	}

	/**
	 * Collect taxes due to the town from it's residents.
	 * 
	 * @param town - Town to collect taxes from
	 * @throws EconomyException - EconomyException
	 */
	protected void collectTownTaxes(Town town) throws EconomyException {
		// Resident Tax
		if (town.getTaxes() > 0) {

			List<Resident> residents = new ArrayList<>(town.getResidents());
			ListIterator<Resident> residentItr = residents.listIterator();
			List<String> removedResidents = new ArrayList<>();
			Resident resident;

			while (residentItr.hasNext()) {
				resident = residentItr.next();

				double tax = town.getTaxes();
				/*
				 * Only collect resident tax from this resident if it really
				 * still exists. We are running in an Async thread so MUST
				 * verify all objects.
				 */
				if (universe.getDataSource().hasResident(resident.getName())) {

					if (TownyPerms.getResidentPerms(resident).containsKey("towny.tax_exempt") || resident.isNPC() || resident.isMayor()) {
						try {
							TownyMessaging.sendResidentMessage(resident, Translation.of("MSG_TAX_EXEMPT"));
						} catch (TownyException e) {
							// Player is not online
						}
						continue;
					} else if (town.isTaxPercentage()) {
						tax = resident.getAccount().getHoldingBalance() * tax / 100;
						
						// Make sure that the town percent tax doesn't remove above the
						// allotted amount of cash.
						tax = Math.min(tax, town.getMaxPercentTaxAmount());

						// Handle if the bank cannot be paid because of the cap. Since it is a % 
						// they will be able to pay but it might be more than the bank can accept,
						// so we reduce it to the amount that the bank can accept, even if it
						// becomes 0.
						if (tax + town.getAccount().getHoldingBalance() > TownySettings.getTownBankCap())
							tax = town.getAccount().getBalanceCap() - town.getAccount().getHoldingBalance();
						
						resident.getAccount().payTo(tax, town, "Town Tax (Percentage)");
					} else {
						// Check if the bank could take the money, reduce it to 0 if required so that 
						// players do not get kicked in a situation they could be paying but cannot because
						// of the bank cap.
						if (tax + town.getAccount().getHoldingBalance() > TownySettings.getTownBankCap())
							tax = town.getAccount().getBalanceCap() - town.getAccount().getHoldingBalance();
						
						if (resident.getAccount().canPayFromHoldings(tax))
							resident.getAccount().payTo(tax, town, "Town tax (FlatRate)");
						else {
							removedResidents.add(resident.getName());					
							// remove this resident from the town.
							resident.removeTown();
						}
					}
				}
			}
			if (removedResidents != null) {
				if (removedResidents.size() == 1) 
					TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_couldnt_pay_tax", removedResidents.get(0), "town"));
				else
					TownyMessaging.sendPrefixedTownMessage(town, ChatTools.list(removedResidents, Translation.of("msg_couldnt_pay_town_tax_multiple")));
			}
		}

		// Plot Tax
		if (town.getPlotTax() > 0 || town.getCommercialPlotTax() > 0 || town.getEmbassyPlotTax() > 0) {

			List<TownBlock> townBlocks = new ArrayList<>(town.getTownBlocks());
			List<String> lostPlots = new ArrayList<>();
			ListIterator<TownBlock> townBlockItr = townBlocks.listIterator();
			TownBlock townBlock;

			while (townBlockItr.hasNext()) {
				townBlock = townBlockItr.next();

				if (!townBlock.hasResident())
					continue;
				try {
					Resident resident = townBlock.getResident();

					/*
					 * Only collect plot tax from this resident if it really
					 * still exists. We are running in an Async thread so MUST
					 * verify all objects.
					 */
					if (universe.getDataSource().hasResident(resident.getName())) {
						if (resident.hasTown() && resident.getTown() == townBlock.getTown())
							if (TownyPerms.getResidentPerms(resident).containsKey("towny.tax_exempt") || resident.isNPC())
								continue;
						
						double tax = townBlock.getType().getTax(town);

						// If the tax would put the town over the bank cap we reduce what will be
						// paid by the plot owner to what will be allowed.
						if (tax + town.getAccount().getHoldingBalance() > TownySettings.getTownBankCap())
							tax = town.getAccount().getBalanceCap() - town.getAccount().getHoldingBalance();
						
						if (!resident.getAccount().payTo(tax, town, String.format("Plot Tax (%s)", townBlock.getType()))) {
							if (!lostPlots.contains(resident.getName()))
								lostPlots.add(resident.getName());

							townBlock.setResident(null);
							townBlock.setPlotPrice(-1);
							// Set the plot permissions to mirror the towns.
							townBlock.setType(townBlock.getType());
							universe.getDataSource().saveTownBlock(townBlock);
						}
					}
				} catch (NotRegisteredException ignored) {
				}
				
			}
			if (lostPlots != null) {
				if (lostPlots.size() == 1) 
					TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_couldnt_pay_plot_taxes", lostPlots.get(0)));
				else
					TownyMessaging.sendPrefixedTownMessage(town, ChatTools.list(lostPlots, Translation.of("msg_couldnt_pay_plot_taxes_multiple")));
			}
		}
	}

	/**
	 * Collect or pay upkeep for all towns.
	 * 
	 * @throws EconomyException if there is an error with the economy handling
	 * @throws TownyException if there is a error with Towny
	 */
	public void collectTownCosts() throws EconomyException, TownyException {
		List<Town> towns = new ArrayList<>(universe.getDataSource().getTowns());
		ListIterator<Town> townItr = towns.listIterator();
		Town town;

		while (townItr.hasNext()) {
			town = townItr.next();

			/*
			 * Only charge/pay upkeep for this town if it really still exists.
			 * We are running in an Async thread so MUST verify all objects.
			 */
			if (universe.getDataSource().hasTown(town.getName())) {

				if (town.hasUpkeep() && !town.isRuined()) {
					double upkeep = TownySettings.getTownUpkeepCost(town);
					double upkeepPenalty = TownySettings.getTownPenaltyUpkeepCost(town);
					if (upkeepPenalty > 0 && upkeep > 0)
						upkeep = upkeep + upkeepPenalty;
				
					totalTownUpkeep = totalTownUpkeep + upkeep;
					if (upkeep > 0) {
						if (town.getAccount().canPayFromHoldings(upkeep)) {
						// Town is able to pay the upkeep.
							town.getAccount().withdraw(upkeep, "Town Upkeep");
							TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_payed_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));
						} else {
						// Town is unable to pay the upkeep.
							if (!TownySettings.isTownBankruptcyEnabled()) {
							// Bankruptcy is disabled, remove the town for not paying upkeep.
								TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_couldnt_pay_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));
								universe.getDataSource().removeTown(town);
								removedTowns.add(town.getName());
								continue;
							}
							
							// Bankruptcy is enabled.
							boolean townWasBankrupt = town.isBankrupt();
							town.getAccount().setDebtCap(MoneyUtil.getEstimatedValueOfTown(town));
						
							if (town.getAccount().getHoldingBalance() - upkeep < town.getAccount().getDebtCap() * -1) {
							// The town will exceed their debt cap to pay the upkeep.
							// Eventually when the cap is reached they will pay 0 upkeep.
													
								if (TownySettings.isUpkeepDeletingTownsThatReachDebtCap()) {
								// Alternatively, if configured, towns will not be allowed to exceed
								// their debt and be deleted from the server for non-payment finally.
									TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_couldnt_pay_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));
									universe.getDataSource().removeTown(town);
									removedTowns.add(town.getName());
									continue;
								}
								upkeep = town.getAccount().getDebtCap() - Math.abs(town.getAccount().getHoldingBalance());
							}
							
							// Finally pay the upkeep or the modified upkeep up to the debtcap. 
							town.getAccount().withdraw(upkeep, "Town Upkeep");
							TownyMessaging.sendPrefixedTownMessage(town, Translation.of("msg_your_town_payed_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));
							
							// Check if the town was newly bankrupted and punish them for it.
							if(!townWasBankrupt && town.isBankrupt()) {
								town.setOpen(false);
								universe.getDataSource().saveTown(town);
								bankruptedTowns.add(town.getName());
							}
						}

						
					} else if (upkeep < 0) {
						// Negative upkeep
						if (TownySettings.isUpkeepPayingPlots()) {
							// Pay each plot owner a share of the negative
							// upkeep
							List<TownBlock> plots = new ArrayList<>(town.getTownBlocks());

							for (TownBlock townBlock : plots) {
								if (townBlock.hasResident())
									townBlock.getResident().getAccount().withdraw((upkeep / plots.size()), "Negative Town Upkeep - Plot income");
								else
									town.getAccount().withdraw((upkeep / plots.size()), "Negative Town Upkeep - Plot income");
							}

						} else {
							// Not paying plot owners so just pay the town
							town.getAccount().withdraw(upkeep, "Negative Town Upkeep");
						}

					}
				}
			}			
		}

		String msg1 = Translation.of("msg_bankrupt_town2");
		String msg2 = Translation.of("msg_bankrupt_town_multiple");
		if(TownySettings.isTownBankruptcyEnabled() && TownySettings.isUpkeepDeletingTownsThatReachDebtCap()) {
				plugin.resetCache(); //Allow perms change to take effect immediately
				msg1 = Translation.of("msg_town_reached_debtcap_and_is_disbanded");
				msg2 = Translation.of("msg_town_reached_debtcap_and_is_disbanded_multiple");
		}
		
		if (bankruptedTowns != null)
			if (bankruptedTowns.size() == 1)
				TownyMessaging.sendGlobalMessage(String.format(Translation.of("msg_town_bankrupt_by_upkeep"), bankruptedTowns.get(0)));
			else
				TownyMessaging.sendGlobalMessage(ChatTools.list(bankruptedTowns, Translation.of("msg_town_bankrupt_by_upkeep_multiple")));
		if (removedTowns != null)
			if (removedTowns.size() == 1)
				TownyMessaging.sendGlobalMessage(String.format(msg1, removedTowns.get(0)));
			else
				TownyMessaging.sendGlobalMessage(ChatTools.list(removedTowns, msg2));
	}

	/**
	 * Collect upkeep due from all nations.
	 * 
	 * @throws EconomyException if there is an error with Economy handling
	 */
	public void collectNationCosts() throws EconomyException {
		List<Nation> nations = new ArrayList<>(universe.getDataSource().getNations());
		ListIterator<Nation> nationItr = nations.listIterator();
		Nation nation;

		while (nationItr.hasNext()) {
			nation = nationItr.next();

			/*
			 * Only charge upkeep for this nation if it really still exists.
			 * We are running in an Async thread so MUST verify all objects.
			 */
			if (universe.getDataSource().hasNation(nation.getName())) {

				double upkeep = TownySettings.getNationUpkeepCost(nation);

				totalNationUpkeep = totalNationUpkeep + upkeep;
				if (upkeep > 0) {
					// Town is paying upkeep
					
					if (nation.getAccount().canPayFromHoldings(upkeep)) {
						nation.getAccount().withdraw(upkeep, "Nation Upkeep");
						TownyMessaging.sendPrefixedNationMessage(nation, Translation.of("msg_your_nation_payed_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));						
					} else {
						TownyMessaging.sendPrefixedNationMessage(nation, Translation.of("msg_your_nation_couldnt_pay_upkeep", TownyEconomyHandler.getFormattedBalance(upkeep)));
						universe.getDataSource().removeNation(nation);
						removedNations.add(nation.getName());
					}

					if (nation.isNeutral()) {
						if (!nation.getAccount().withdraw(TownySettings.getNationNeutralityCost(), "Nation Peace Upkeep")) {
							nation.setNeutral(false);
							universe.getDataSource().saveNation(nation);
							TownyMessaging.sendPrefixedNationMessage(nation, Translation.of("msg_nation_not_peaceful"));
						}
					}
					
				} else if (upkeep < 0) {
					nation.getAccount().withdraw(upkeep, "Negative Nation Upkeep");
				}
			}
		}
		if (removedNations != null && !removedNations.isEmpty()) {
			if (removedNations.size() == 1)
				TownyMessaging.sendGlobalMessage(Translation.of("msg_bankrupt_nation2", removedNations.get(0)));
			else
				TownyMessaging.sendGlobalMessage(ChatTools.list(removedNations, Translation.of("msg_bankrupt_nation_multiple")));
		}
	}
}
