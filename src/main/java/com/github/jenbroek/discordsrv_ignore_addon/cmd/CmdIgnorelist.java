package com.github.jenbroek.discordsrv_ignore_addon.cmd;

import com.github.jenbroek.discordsrv_ignore_addon.DiscordsrvIgnoreAddon;
import github.scarsz.discordsrv.DiscordSRV;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.github.jenbroek.discordsrv_ignore_addon.data.Message.CHAT_HIDDEN_NOTICE;
import static com.github.jenbroek.discordsrv_ignore_addon.data.Message.LIST_IGNORED_EMPTY;
import static com.github.jenbroek.discordsrv_ignore_addon.data.Message.LIST_IGNORED_TEMPLATE;

public class CmdIgnorelist implements TabExecutor {

	private final DiscordsrvIgnoreAddon plugin;

	public CmdIgnorelist(DiscordsrvIgnoreAddon plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(
		@NotNull CommandSender sender,
		@NotNull Command command,
		@NotNull String label,
		@NotNull String[] args
	) {
		if (!(sender instanceof Player player)) {
			sender.sendMessage("You must be a player to use this command!");
		} else {
			var mcUid = player.getUniqueId();
			var ignoring = plugin.getIgnoring().getOrDefault(mcUid, ConcurrentHashMap.newKeySet());

			CompletableFuture.supplyAsync(
				() -> String.join(
					", ",
					ignoring.stream()
					        .map(this::tryGetMinecraftName)
					        .toList() // Collect first so `join()` is done in parallel
					        .stream()
					        .map(CompletableFuture::join)
					        .toList()
				)
			).thenAcceptAsync(s -> {
				if (s.isEmpty()) {
					player.sendMessage(LIST_IGNORED_EMPTY.asComponent(plugin.getConfig()));
				} else {
					player.sendMessage(LIST_IGNORED_TEMPLATE.asComponent(plugin.getConfig(), s));
				}

				if (plugin.getUnsubscribed().contains(mcUid)) {
					player.sendMessage(CHAT_HIDDEN_NOTICE.asComponent(plugin.getConfig()));
				}
			}, Bukkit.getScheduler().getMainThreadExecutor(plugin));
		}

		return true;
	}

	@Override
	public @Nullable List<String> onTabComplete(
		@NotNull CommandSender sender,
		@NotNull Command command,
		@NotNull String label,
		@NotNull String[] args
	) {
		// Inhibit default completions
		return List.of();
	}

	private CompletableFuture<String> tryGetMinecraftName(String discordUid) {
		return CompletableFuture
			.supplyAsync(() -> DiscordSRV.getPlugin().getAccountLinkManager().getUuid(discordUid))
			.exceptionally(t -> null)
			.thenApplyAsync(mcUid -> {
				if (mcUid == null) return discordUid;
				var name = Bukkit.getOfflinePlayer(mcUid).getName();
				return name != null ? name : discordUid;
			}, Bukkit.getScheduler().getMainThreadExecutor(plugin)); // Ensure running on Bukkit's main thread
	}

}
