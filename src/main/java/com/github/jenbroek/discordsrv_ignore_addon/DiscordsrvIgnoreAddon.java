package com.github.jenbroek.discordsrv_ignore_addon;

import com.github.jenbroek.discordsrv_ignore_addon.cmd.CmdIgnore;
import com.github.jenbroek.discordsrv_ignore_addon.cmd.CmdIgnorelist;
import com.github.jenbroek.discordsrv_ignore_addon.cmd.CmdToggle;
import com.github.jenbroek.discordsrv_ignore_addon.data.JedisSimpleMap;
import com.github.jenbroek.discordsrv_ignore_addon.data.JedisSimpleSet;
import com.github.jenbroek.discordsrv_ignore_addon.data.RetryingExecutorService;
import com.github.jenbroek.discordsrv_ignore_addon.data.SimpleMap;
import com.github.jenbroek.discordsrv_ignore_addon.data.SimpleSet;
import github.scarsz.discordsrv.DiscordSRV;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

public final class DiscordsrvIgnoreAddon extends JavaPlugin implements Listener {

	public static final String DEF_REDIS_HOST = "localhost";
	public static final int DEF_REDIS_PORT = 6379;
	public static final String DEF_REDIS_USER = null;
	public static final String DEF_REDIS_PASSWORD = null;
	public static final int DEF_REDIS_MAX_TOTAL_CONNECTIONS = JedisPoolConfig.DEFAULT_MAX_TOTAL;
	public static final int DEF_REDIS_MAX_IDLE_DURATION = 10; // In minutes
	public static final int DEF_REDIS_RETRY_DELAY = 30; // In minutes
	private static final String DEF_REDIS_NAMESPACE = "discordsrv-ignore-addon";

	private final DiscordListener discordListener = new DiscordListener(this);

	private UnifiedJedis jedis;
	private ScheduledExecutorService executor;

	private JedisSimpleSet<UUID> unsubscribed;
	private JedisSimpleMap<UUID, Set<String>> ignoring;

	@Override
	public void onDisable() {
		DiscordSRV.api.unsubscribe(discordListener);
		if (executor != null) executor.shutdown();
		if (jedis != null) jedis.close();
	}

	@Override
	public void onEnable() {
		DiscordSRV.api.subscribe(discordListener);

		Objects.requireNonNull(getCommand("discordtoggle")).setExecutor(new CmdToggle(this));
		Objects.requireNonNull(getCommand("discordignore")).setExecutor(new CmdIgnore(this));
		Objects.requireNonNull(getCommand("discordignorelist")).setExecutor(new CmdIgnorelist(this));

		saveDefaultConfig();

		try {
			jedis = initializeRedis(getConfig());
			jedis.ping();
		} catch (JedisConnectionException e) {
			getLogger().severe("Failed to connect to Redis during setup: " + e.getMessage());
			getLogger().warning("Disabling plugin...");
			Bukkit.getPluginManager().disablePlugin(this);
			return;
		}

		var retryDelay = getConfig().getInt("redis.retry-delay", DEF_REDIS_RETRY_DELAY);
		executor = new RetryingExecutorService(
			this,
			4,
			Duration.of(retryDelay, ChronoUnit.MINUTES),
			this::retryIfPossible
		);

		var namespace = getConfig().getString("redis.namespace", DEF_REDIS_NAMESPACE);
		unsubscribed = new JedisSimpleSet<>(
			jedis,
			namespace + ":unsubscribed",
			this.executor,
			ConcurrentHashMap.newKeySet(),
			UUID::toString,
			UUID::fromString
		);
		ignoring = new JedisSimpleMap<>(
			jedis,
			namespace + ":ignoring",
			this.executor,
			new ConcurrentHashMap<>(),
			UUID::toString,
			UUID::fromString,
			s -> String.join(";", s),
			s -> Arrays.stream(s.split(";")).collect(Collectors.toSet())
		);
	}

	public SimpleSet<UUID> getUnsubscribed() {
		return unsubscribed;
	}

	public SimpleMap<UUID, Set<String>> getIgnoring() {
		return ignoring;
	}

	private boolean retryIfPossible(Throwable throwable) {
		switch (throwable) {
		case JedisConnectionException e:
			getLogger().warning("Failed to connect to Redis: " + e.getMessage());
			getLogger().warning("Retrying later...");
			return true;
		case JedisAccessControlException e:
			getLogger().warning("Error while authenticating to Redis: " + e.getMessage());
			getLogger().warning("Retrying later...");
			return true;
		case JedisException e:
			getLogger().severe("Failed to sync to Redis: " + e.getMessage());
			getLogger().warning("Disabling plugin...");
			executor.shutdownNow();
			Bukkit.getScheduler().runTask(this, () -> Bukkit.getPluginManager().disablePlugin(this));
			return false;
		default:
			getLogger().severe("Uncaught exception: " + throwable.getMessage());
			return false;
		}
	}

	private static UnifiedJedis initializeRedis(FileConfiguration cfg) {
		var host = cfg.getString("redis.host", DEF_REDIS_HOST);
		var port = cfg.getInt("redis.port", DEF_REDIS_PORT);
		var user = cfg.getString("redis.user", DEF_REDIS_USER);
		var password = cfg.getString("redis.password", DEF_REDIS_PASSWORD);
		var maxTotalConnections = cfg.getInt("redis.max-total-connections", DEF_REDIS_MAX_TOTAL_CONNECTIONS);
		var maxIdleDuration = cfg.getLong("redis.max-idle-duration", DEF_REDIS_MAX_IDLE_DURATION);

		var jedisCfg = DefaultJedisClientConfig.builder()
		                                       .user(user)
		                                       .password(password)
		                                       .build();

		var jedis = new JedisPooled(new HostAndPort(host, port), jedisCfg);
		jedis.getPool().setMaxTotal(maxTotalConnections);
		jedis.getPool().setMaxIdle(maxTotalConnections);
		jedis.getPool().setDurationBetweenEvictionRuns(Duration.of(maxIdleDuration, ChronoUnit.MINUTES));

		return jedis;
	}

}
