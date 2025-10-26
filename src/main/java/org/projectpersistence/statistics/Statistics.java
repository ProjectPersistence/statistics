package org.projectpersistence.statistics;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;

import java.sql.*;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

public class Statistics implements ModInitializer {

    private static final String DATABASE_PATH = "statistics.db";
    private static final int SWITCH_INTERVAL = 100; // Ticks (5 seconds)
    private static int tickCounter = 0;
    private static boolean showMined = true;

    private static final Map<UUID, PlayerStats> statsCache = new HashMap<>();
    private static ScoreboardObjective currentObjective;

    @Override
    public void onInitialize() {
        System.out.println("[Statistics] Initializing...");
        DatabaseManager.init();

        // Track mined blocks
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
                DatabaseManager.incrementMined(serverPlayer.getUuid());
                updateStatsCache(serverPlayer.getUuid());
            }
        });

        // Track block placements
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity serverPlayer) {
                var stack = serverPlayer.getStackInHand(hand);
                if (stack.getItem() instanceof BlockItem) {
                    DatabaseManager.incrementPlaced(serverPlayer.getUuid());
                    updateStatsCache(serverPlayer.getUuid());
                }
            }
            return ActionResult.PASS;
        });

        // Server tick event to update display
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            if (tickCounter >= SWITCH_INTERVAL) {
                tickCounter = 0;
                showMined = !showMined;
                updateAllPlayerDisplays(server);
            }
        });

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    private void updateStatsCache(UUID uuid) {
        PlayerStats stats = DatabaseManager.getStats(uuid);
        statsCache.put(uuid, stats);
    }

    private void updateAllPlayerDisplays(net.minecraft.server.MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();

        // Remove old objective if exists
        if (currentObjective != null) {
            scoreboard.removeObjective(currentObjective);
        }

        // Create new objective with appropriate name
        String objectiveName = showMined ? "stats_mined" : "stats_placed";
        String displayText = showMined ? "§8Blocks Mined" : "§8Blocks Placed";

        // Check if objective already exists and remove it
        ScoreboardObjective existingObjective = scoreboard.getNullableObjective(objectiveName);
        if (existingObjective != null) {
            scoreboard.removeObjective(existingObjective);
        }

        currentObjective = scoreboard.addObjective(
                objectiveName,
                ScoreboardCriterion.DUMMY,
                Text.literal(displayText),
                ScoreboardCriterion.RenderType.INTEGER,
                false,
                new StyledNumberFormat(net.minecraft.text.Style.EMPTY.withColor(net.minecraft.util.Formatting.DARK_GREEN))
        );

        scoreboard.setObjectiveSlot(ScoreboardDisplaySlot.BELOW_NAME, currentObjective);

        // Update scores for all online players
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            updatePlayerDisplay(player, scoreboard);
        }
    }

    private void updatePlayerDisplay(ServerPlayerEntity player, Scoreboard scoreboard) {
        if (currentObjective == null) return;

        UUID uuid = player.getUuid();
        PlayerStats stats = statsCache.computeIfAbsent(uuid, id -> DatabaseManager.getStats(id));

        int value = showMined ? stats.blocksMined : stats.blocksPlaced;
        scoreboard.getOrCreateScore(player, currentObjective).setScore(value);
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("statistics")
                // /statistics
                .executes(context -> {
                    ServerPlayerEntity player = context.getSource().getPlayer();
                    if (player == null) return 0;

                    PlayerStats stats = DatabaseManager.getStats(player.getUuid());
                    sendPlayerStats(player.getName().getString(), stats, context.getSource());
                    return 1;
                })

                // /statistics credits
                .then(CommandManager.literal("credits")
                        .executes(context -> {
                            MessageUtils.info(context.getSource(),
                                    "Credits: https://github.com/ProjectPersistence");
                            return 1;
                        }))

                // /statistics set [Mined|Placed] <playername> <number>
                .then(CommandManager.literal("set")
                        .requires(source -> source.hasPermissionLevel(2) || hasAdminPermission(source))
                        .then(CommandManager.literal("Mined")
                                .then(CommandManager.argument("playername", StringArgumentType.string())
                                        .then(CommandManager.argument("number", IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    String playerName = StringArgumentType.getString(context, "playername");
                                                    int value = IntegerArgumentType.getInteger(context, "number");
                                                    return setStat(context.getSource(), playerName, "mined", value);
                                                }))))
                        .then(CommandManager.literal("Placed")
                                .then(CommandManager.argument("playername", StringArgumentType.string())
                                        .then(CommandManager.argument("number", IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    String playerName = StringArgumentType.getString(context, "playername");
                                                    int value = IntegerArgumentType.getInteger(context, "number");
                                                    return setStat(context.getSource(), playerName, "placed", value);
                                                }))))
                )

                // /statistics <playername>
                .then(CommandManager.argument("playername", StringArgumentType.string())
                        .executes(context -> {
                            String targetName = StringArgumentType.getString(context, "playername");
                            ServerPlayerEntity target = context.getSource().getServer().getPlayerManager().getPlayer(targetName);

                            if (target == null) {
                                MessageUtils.error(context.getSource(), "Player not found.");
                                return 0;
                            }

                            PlayerStats stats = DatabaseManager.getStats(target.getUuid());
                            sendPlayerStats(targetName, stats, context.getSource());
                            return 1;
                        }))
        );
    }

    private boolean hasAdminPermission(ServerCommandSource source) {
        return source.hasPermissionLevel(2);
    }

    private int setStat(ServerCommandSource source, String playerName, String type, int value) {
        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);

        if (player == null) {
            MessageUtils.error(source, "Player not found or not online!");
            return 0;
        }

        UUID uuid = player.getUuid();
        if (type.equalsIgnoreCase("mined")) {
            DatabaseManager.setMined(uuid, value);
        } else if (type.equalsIgnoreCase("placed")) {
            DatabaseManager.setPlaced(uuid, value);
        }

        updateStatsCache(uuid);
        updatePlayerDisplay(player, source.getServer().getScoreboard());

        MessageUtils.info(source, playerName + " has been set to " + value + " " + type + " blocks.");
        return 1;
    }

    // Multi-line stats display
    private void sendPlayerStats(String playerName, PlayerStats stats, ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§8|------------------[§aSTATISTICS§8]------------------|"), false);
        source.sendFeedback(() -> Text.literal("§8| §3" + playerName + " §8has §2" + stats.blocksMined + " §8Blocks Mined"), false);
        source.sendFeedback(() -> Text.literal("§8| §3" + playerName + " §8has §2" + stats.blocksPlaced + " §8Blocks Placed"), false);
        source.sendFeedback(() -> Text.literal("§8|-----------------------------------------------|"), false);
    }

    // Message helper for single-line messages
    public static class MessageUtils {
        public static void info(ServerCommandSource source, String message) {
            source.sendFeedback(() ->
                            Text.literal("§8[§aSTATISTICS§8] §3" + message),
                    false
            );
        }

        public static void warn(ServerCommandSource source, String message) {
            source.sendFeedback(() ->
                            Text.literal("§8[§eSTATISTICS§8] §8" + message),
                    false
            );
        }

        public static void error(ServerCommandSource source, String message) {
            source.sendError(Text.literal("§8[§cSTATISTICS§8] " + message));
        }

        public static void critical(ServerCommandSource source, String message) {
            source.sendError(Text.literal("§8[§4STATISTICS§8] §7" + message));
        }
    }

    // Data holder
    public static class PlayerStats {
        public int blocksMined;
        public int blocksPlaced;

        public PlayerStats(int mined, int placed) {
            this.blocksMined = mined;
            this.blocksPlaced = placed;
        }
    }

    // SQLite database manager
    public static class DatabaseManager {
        private static Connection connection;

        public static void init() {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                try (Statement stmt = connection.createStatement()) {
                    stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS player_stats (
                            uuid TEXT PRIMARY KEY,
                            mined INTEGER DEFAULT 0,
                            placed INTEGER DEFAULT 0
                        )
                    """);
                }
                System.out.println("[Statistics] Database initialized successfully.");
            } catch (SQLException e) {
                System.err.println("[Statistics] Database initialization failed:");
                e.printStackTrace();
            }
        }

        public static void incrementMined(UUID uuid) { increment(uuid, "mined"); }
        public static void incrementPlaced(UUID uuid) { increment(uuid, "placed"); }

        private static void increment(UUID uuid, String column) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO player_stats (uuid, mined, placed) " +
                            "VALUES (?, 0, 0) " +
                            "ON CONFLICT(uuid) DO UPDATE SET " + column + " = " + column + " + 1")) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        public static PlayerStats getStats(UUID uuid) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT mined, placed FROM player_stats WHERE uuid = ?")) {
                stmt.setString(1, uuid.toString());
                ResultSet rs = stmt.executeQuery();

                if (rs.next()) {
                    return new PlayerStats(rs.getInt("mined"), rs.getInt("placed"));
                } else {
                    return new PlayerStats(0, 0);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return new PlayerStats(0, 0);
            }
        }

        public static void setMined(UUID uuid, int value) { set(uuid, "mined", value); }
        public static void setPlaced(UUID uuid, int value) { set(uuid, "placed", value); }

        private static void set(UUID uuid, String column, int value) {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO player_stats (uuid, mined, placed) " +
                            "VALUES (?, 0, 0) " +
                            "ON CONFLICT(uuid) DO UPDATE SET " + column + " = ?")) {
                stmt.setString(1, uuid.toString());
                stmt.setInt(2, value);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}