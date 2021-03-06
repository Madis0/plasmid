package xyz.nucleoid.plasmid.game;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.config.PlayerConfig;
import xyz.nucleoid.plasmid.game.event.GameTickListener;
import xyz.nucleoid.plasmid.game.event.OfferPlayerListener;
import xyz.nucleoid.plasmid.game.event.PlayerRemoveListener;
import xyz.nucleoid.plasmid.game.event.RequestStartListener;
import xyz.nucleoid.plasmid.game.player.JoinResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRule;
import xyz.nucleoid.plasmid.game.rule.RuleResult;
import xyz.nucleoid.plasmid.widget.BossBarWidget;
import xyz.nucleoid.plasmid.widget.GlobalWidgets;

public final class GameWaitingLobby {
    private static final Text WAITING_TITLE = new TranslatableText("text.plasmid.game.waiting_lobby.bar.waiting");

    private static final int START_REQUESTED_COUNTDOWN = 20 * 3;

    private final GameSpace gameSpace;
    private final PlayerConfig playerConfig;

    private final BossBarWidget bar;
    private long countdownStart = -1;
    private long countdownDuration = -1;

    private boolean startRequested;
    private boolean started;

    private GameWaitingLobby(GameSpace gameSpace, PlayerConfig playerConfig, BossBarWidget bar) {
        this.gameSpace = gameSpace;
        this.playerConfig = playerConfig;
        this.bar = bar;
    }

    public static void applyTo(GameLogic logic, PlayerConfig playerConfig) {
        GlobalWidgets widgets = new GlobalWidgets(logic);
        BossBarWidget bar = widgets.addBossBar(WAITING_TITLE);

        GameWaitingLobby lobby = new GameWaitingLobby(logic.getSpace(), playerConfig, bar);

        logic.setRule(GameRule.CRAFTING, RuleResult.DENY);
        logic.setRule(GameRule.PORTALS, RuleResult.DENY);
        logic.setRule(GameRule.PVP, RuleResult.DENY);
        logic.setRule(GameRule.FALL_DAMAGE, RuleResult.DENY);
        logic.setRule(GameRule.HUNGER, RuleResult.DENY);
        logic.setRule(GameRule.THROW_ITEMS, RuleResult.DENY);
        logic.setRule(GameRule.INTERACTION, RuleResult.DENY);
        logic.setRule(GameRule.PLACE_BLOCKS, RuleResult.DENY);
        logic.setRule(GameRule.BREAK_BLOCKS, RuleResult.DENY);

        logic.on(GameTickListener.EVENT, lobby::onTick);
        logic.on(RequestStartListener.EVENT, lobby::requestStart);
        logic.on(OfferPlayerListener.EVENT, lobby::offerPlayer);
        logic.on(PlayerRemoveListener.EVENT, lobby::onRemovePlayer);
    }

    private void onTick() {
        if (this.started) {
            return;
        }

        long time = this.gameSpace.getWorld().getTime();

        if (this.countdownStart != -1 && time >= this.countdownStart + this.countdownDuration) {
            this.started = true;
            this.gameSpace.requestStart().thenAccept(startResult -> {
                if (startResult.isError()) {
                    MutableText message = new TranslatableText("text.plasmid.game.waiting_lobby.bar.cancel").append(startResult.getError());
                    this.gameSpace.getPlayers().sendMessage(message.formatted(Formatting.RED));
                    this.started = false;
                    this.startRequested = false;
                    this.countdownStart = -1;
                }
            });
        }

        if (time % 20 == 0) {
            this.updateCountdown();
            this.tickCountdownBar();
            this.tickCountdownSound();
        }
    }

    @Nullable
    private StartResult requestStart() {
        if (this.gameSpace.getPlayerCount() < this.playerConfig.getMinPlayers()) {
            return StartResult.NOT_ENOUGH_PLAYERS;
        }

        if (!this.started) {
            // consume the start request but initiate countdown
            this.startRequested = true;
            return StartResult.OK;
        } else {
            // we allow the actual start logic to pass through now
            return null;
        }
    }

    private JoinResult offerPlayer(ServerPlayerEntity player) {
        if (this.isFull()) {
            return JoinResult.gameFull();
        }

        this.updateCountdown();
        return JoinResult.ok();
    }

    private void onRemovePlayer(ServerPlayerEntity player) {
        this.updateCountdown();
    }

    private void updateCountdown() {
        long targetDuration = this.getTargetCountdownDuration();
        if (targetDuration != this.countdownDuration) {
            this.updateCountdown(targetDuration);
        }
    }

    private void updateCountdown(long targetDuration) {
        if (targetDuration != -1) {
            long time = this.gameSpace.getWorld().getTime();
            long startTime = time;

            if (this.countdownStart != -1) {
                long countdownEnd = this.countdownStart + this.countdownDuration;
                long timeRemaining = countdownEnd - time;

                long remainingDuration = Math.min(timeRemaining, targetDuration);
                startTime = Math.min(time, time + remainingDuration - targetDuration);
            }

            this.countdownStart = startTime;
            this.countdownDuration = targetDuration;
        } else {
            this.countdownStart = -1;
            this.countdownDuration = -1;
        }
    }

    private long getTargetCountdownDuration() {
        PlayerConfig.Countdown countdown = this.playerConfig.getCountdown();
        if (this.startRequested) {
            return START_REQUESTED_COUNTDOWN;
        }

        if (this.gameSpace.getPlayerCount() >= this.playerConfig.getMinPlayers()) {
            if (this.isFull()) {
                return countdown.getFullTicks();
            } else if (this.isReady()) {
                return countdown.getReadyTicks();
            }
        }

        return -1;
    }

    private void tickCountdownBar() {
        if (this.countdownStart != -1) {
            long time = this.gameSpace.getWorld().getTime();
            long remainingTicks = this.getRemainingTicks(time);
            long remainingSeconds = remainingTicks / 20;

            this.bar.setTitle(new TranslatableText("text.plasmid.game.waiting_lobby.bar.countdown", remainingSeconds));
            this.bar.setProgress((float) remainingTicks / this.countdownDuration);
        } else {
            this.bar.setTitle(WAITING_TITLE);
            this.bar.setProgress(1.0F);
        }
    }

    private void tickCountdownSound() {
        if (this.countdownStart != -1) {
            long time = this.gameSpace.getWorld().getTime();
            long remainingSeconds = this.getRemainingTicks(time) / 20;

            if (remainingSeconds <= 3) {
                PlayerSet players = this.gameSpace.getPlayers();

                float pitch = remainingSeconds == 0 ? 1.5F : 1.0F;
                players.sendSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0F, pitch);
            }
        }
    }

    private long getRemainingTicks(long time) {
        return Math.max(this.countdownStart + this.countdownDuration - time, 0);
    }

    private boolean isReady() {
        return this.gameSpace.getPlayerCount() >= this.playerConfig.getThresholdPlayers();
    }

    private boolean isFull() {
        return this.gameSpace.getPlayerCount() >= this.playerConfig.getMaxPlayers()
                || this.gameSpace.getPlayerCount() >= this.gameSpace.getServer().getCurrentPlayerCount();
    }
}
