package me.vsmcpl.discordstatus;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.Color;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class VSMPLDiscordStatus extends JavaPlugin {

    // JDA instance for the Discord bot
    private JDA jda;
    // Bukkit task for scheduling periodic updates
    private BukkitTask updateTask;
    // Server start time for uptime calculation
    private long startTime;

    // --- Nested Helper Classes and Enums ---

    /**
     * Represents the server's state for the Discord embed.
     */
    public enum ServerStatus {
        ONLINE, OFFLINE, STARTING
    }

    /**
     * An immutable data record to hold a snapshot of server information.
     * This is used to safely pass data from the main server thread to an async thread.
     */
    public record ServerSnapshot(
            String version,
            int onlinePlayers,
            int maxPlayers,
            boolean whitelist,
            String uptime
    ) {}


    // --- Plugin Lifecycle Methods (onEnable, onDisable) ---

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        if (isConfigInvalid(config)) {
            getLogger().severe("Plugin disabling due to invalid configuration. Please check your config.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.startTime = System.currentTimeMillis();

        // Initialize and start the Discord bot on a separate thread to avoid freezing the server.
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            startBot();
            if (isBotReady()) {
                getLogger().info("Discord bot connected successfully.");
                // Send an initial "Starting..." message.
                updateStatus(ServerStatus.STARTING, null);
                // Schedule the repeating update task.
                startUpdateTask();
            } else {
                getLogger().severe("Discord bot failed to connect. The plugin will not send status updates.");
            }
        });
    }

    @Override
    public void onDisable() {
        if (updateTask != null && !updateTask.isCancelled()) {
            updateTask.cancel();
        }

        // Send a final "Offline" status message before the server shuts down.
        if (isBotReady()) {
            getLogger().info("Sending final offline status to Discord...");
            try {
                RestAction<?> action = updateStatus(ServerStatus.OFFLINE, null);
                if (action != null) {
                    // .complete() blocks the thread, ensuring the message is sent before the plugin disables.
                    action.complete();
                    getLogger().info("Offline status sent successfully.");
                }
            } catch (Exception e) {
                getLogger().warning("Could not send final offline status to Discord: " + e.getMessage());
            }
        }

        shutdownBot();
        getLogger().info("Plugin has been disabled.");
    }


    // --- Core Bot and Task Management ---

    /**
     * Starts the JDA bot instance.
     */
    private void startBot() {
        try {
            jda = JDABuilder.createDefault(getConfig().getString("bot-token"))
                    .enableIntents(GatewayIntent.GUILD_MESSAGES) // Required to see channel messages
                    .build()
                    .awaitReady(); // Waits until the bot is fully connected
        } catch (InterruptedException e) {
            getLogger().severe("JDA startup was interrupted!");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Catches login exceptions, etc.
            getLogger().severe("Failed to start JDA. Is the token valid? Error: " + e.getMessage());
        }
    }

    /**
     * Shuts down the JDA instance gracefully.
     */
    private void shutdownBot() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    /**
     * Checks if the JDA instance is running and connected.
     */
    private boolean isBotReady() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }

    /**
     * Schedules the repeating task to update the Discord status.
     */
    private void startUpdateTask() {
        long interval = getConfig().getLong("update-interval", 60);
        this.updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                // To avoid unsafe, async calls to the Bukkit API, we must gather the data on the main thread first.
                // We do this by scheduling a synchronous task and waiting for its result.
                java.util.concurrent.Future<ServerSnapshot> future = Bukkit.getScheduler().callSyncMethod(this, () -> {
                    // 1. This code runs safely on the main server thread.
                    return new ServerSnapshot(
                            Bukkit.getBukkitVersion().split("-")[0],
                            Bukkit.getOnlinePlayers().size(),
                            Bukkit.getMaxPlayers(),
                            Bukkit.hasWhitelist(),
                            getUptimeString()
                    );
                });

                // 2. .get() blocks this async thread (which is safe) until the main thread finishes and returns the snapshot.
                ServerSnapshot snapshot = future.get();

                // 3. Now that we have the data, send it to Discord from this async thread.
                updateStatus(ServerStatus.ONLINE, snapshot);
            } catch (Exception e) {
                getLogger().warning("Failed to get server snapshot for Discord update: " + e.getMessage());
            }
        }, 20L * 10, 20L * interval); // 10-second initial delay, then updates at the configured interval
    }


    // --- Discord Message Handling ---

    /**
     * Builds and sends/updates the status embed in the configured Discord channel.
     *
     * @param status   The current status of the server.
     * @param snapshot A snapshot of server data (can be null for OFFLINE/STARTING status).
     * @return A RestAction that completes when the message is sent/edited.
     */
    private RestAction<Message> updateStatus(ServerStatus status, ServerSnapshot snapshot) {
        if (!isBotReady()) return null;

        TextChannel channel = jda.getTextChannelById(getConfig().getString("status-channel-id", ""));
        if (channel == null) {
            getLogger().warning("Status channel ID is invalid or the bot can't see it.");
            return null;
        }

        EmbedBuilder embed = buildEmbed(status, snapshot);
        String messageId = getConfig().getString("status-message-id", "");

        if (messageId.isEmpty()) {
            // No message ID saved, so send a new message and handle success/failure
            RestAction<Message> action = channel.sendMessageEmbeds(embed.build());
            action.queue(
                    (message) -> { // Success
                        getConfig().set("status-message-id", message.getId());
                        saveConfig();
                        getLogger().info("Created new status message with ID: " + message.getId());
                    },
                    (error) -> { // Failure
                        getLogger().warning("Failed to send initial status message: " + error.getMessage());
                    }
            );
            return action;
        } else {
            // Edit the existing message
            RestAction<Message> action = channel.editMessageEmbedsById(messageId, embed.build());
            action.queue(
                    null, // Don't need to do anything on success
                    (error) -> getLogger().warning("Failed to edit status message (check permissions or if the message was deleted): " + error.getMessage())
            );
            return action;
        }
    }

    /**
     * Constructs the Discord embed with all the server information.
     */
    private EmbedBuilder buildEmbed(ServerStatus status, ServerSnapshot snapshot) {
        EmbedBuilder embed = new EmbedBuilder();
        FileConfiguration config = getConfig();

        // Set embed color and title based on status
        switch (status) {
            case ONLINE -> {
                embed.setColor(new Color(15, 255, 0)); // Green
                embed.setTitle("✅ Server is Online");
            }
            case OFFLINE -> {
                embed.setColor(new Color(255, 00, 00)); // Red
                embed.setTitle("❌ Server is Offline");
            }
            case STARTING -> {
                embed.setColor(new Color(255, 224, 0)); // Yellow
                embed.setTitle("⏳ Server is Starting...");
            }
        }

        embed.addField("Server Name", config.getString("server-info.name", "N/A"), true);
        embed.addField("Platform", config.getString("server-info.platform", "N/A"), true);
        embed.addField("IP Address", "`" + config.getString("server-info.ip", "N/A") + "`", true);

        // Only add live server data if we have a snapshot
        if (snapshot != null) {
            embed.addField("Version", snapshot.version(), true);
            embed.addField("Players", snapshot.onlinePlayers() + " / " + snapshot.maxPlayers(), true);
            embed.addField("Whitelist", snapshot.whitelist() ? "On" : "Off", true);
            embed.addField("Uptime", snapshot.uptime(), false);
        }

        String bannerUrl = config.getString("server-info.banner-url");
        if (bannerUrl != null && !bannerUrl.isEmpty()) {
            embed.setImage(bannerUrl);
        }
        embed.setTimestamp(Instant.now());
        embed.setFooter("Last Updated");

        return embed;
    }


    // --- Utility Methods ---

    /**
     * Validates that the essential configuration options are set.
     */
    private boolean isConfigInvalid(FileConfiguration config) {
        String token = config.getString("bot-token", "");
        String channelId = config.getString("status-channel-id", "");
        return token.isEmpty() || token.equals("YOUR_BOT_TOKEN_HERE") ||
                channelId.isEmpty() || channelId.equals("YOUR_CHANNEL_ID_HERE");
    }

    /**
     * Calculates and formats the server's uptime.
     */
    private String getUptimeString() {
        long uptimeMillis = System.currentTimeMillis() - startTime;
        long days = TimeUnit.MILLISECONDS.toDays(uptimeMillis);
        uptimeMillis -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(uptimeMillis);
        uptimeMillis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(uptimeMillis);

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        sb.append(minutes).append("m");
        return sb.toString();
    }
}