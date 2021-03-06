package xyz.nucleoid.plasmid.game;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.UnboundedMapCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.error.ErrorReporter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ConfiguredGame<C> {
    public static final Codec<ConfiguredGame<?>> CODEC = new ConfigCodec(null).codec();

    @Nullable
    private final Identifier source;

    private final GameType<C> type;
    @Nullable
    private final String name;
    @Nullable
    private final String translation;

    private final Map<Identifier, Dynamic<?>> custom;

    private final C config;

    private ConfiguredGame(
            @Nullable Identifier source,
            GameType<C> type,
            @Nullable String name,
            @Nullable String translation,
            Map<Identifier, Dynamic<?>> custom,
            C config
    ) {
        this.source = source;
        this.type = type;
        this.name = name;
        this.translation = translation;
        this.custom = custom;
        this.config = config;
    }

    public static Codec<ConfiguredGame<?>> codecFrom(Identifier source) {
        return new ConfigCodec(source).codec();
    }

    public GameOpenProcedure openProcedure(MinecraftServer server) {
        GameOpenContext<C> context = new GameOpenContext<>(server, this);
        return this.type.open(context);
    }

    public CompletableFuture<ManagedGameSpace> open(MinecraftServer server) {
        CompletableFuture<ManagedGameSpace> future = CompletableFuture.supplyAsync(() -> this.openProcedure(server), Util.getMainWorkerExecutor())
                .thenCompose(GameOpenProcedure::open);

        future.exceptionally(throwable -> {
            if (GameOpenException.unwrap(throwable) == null) {
                try (ErrorReporter reporter = ErrorReporter.open(this)) {
                    reporter.report(throwable, "Opening game");
                }
            }
            return null;
        });

        return future;
    }

    /**
     * @return the source location that this config was loaded from, if loaded from a file.
     */
    @Nullable
    public Identifier getSource() {
        return this.source;
    }

    public GameType<C> getType() {
        return this.type;
    }

    // TODO: Remove in 0.5 - replaced with Text variants
    @Deprecated
    public String getName() {
        return this.name != null ? this.name : this.type.getIdentifier().toString();
    }

    /**
     * @return An {@link Optional} containing the name of the game config, if specified.
     * @deprecated use {@link Text}-returning version
     */
    @Deprecated
    public Optional<String> getOptionalName() {
        return Optional.ofNullable(this.name);
    }

    /**
     * @param id The game ID of the current {@link ConfiguredGame}
     * @return The name of the game as specified in the config, or the provided {@link Identifier} if it was not.
     * @deprecated use {@link Text}-returning version
     */
    @Deprecated
    public String getDisplayName(Identifier id) {
        return this.getOptionalName().orElseGet(id::toString);
    }

    /**
     * @return the name for this game config, defaulted to the game type name if none is specified
     */
    public Text getNameText() {
        if (this.name != null) {
            return new LiteralText(this.name);
        } else if (this.translation != null) {
            return new TranslatableText(this.translation);
        } else {
            return this.type.getName();
        }
    }

    public <T> Optional<T> getCustomValue(Codec<T> codec, Identifier key) {
        Dynamic<?> value = this.custom.get(key);
        if (value != null) {
            return codec.decode(value).result().map(Pair::getFirst);
        } else {
            return Optional.empty();
        }
    }

    public C getConfig() {
        return this.config;
    }

    static final class ConfigCodec extends MapCodec<ConfiguredGame<?>> {
        private static final UnboundedMapCodec<Identifier, Dynamic<?>> CUSTOM_VALUES_CODEC = Codec.unboundedMap(Identifier.CODEC, Codec.PASSTHROUGH);

        private final Identifier source;

        ConfigCodec(Identifier source) {
            this.source = source;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.of(
                    ops.createString("type"),
                    ops.createString("name"),
                    ops.createString("translation"),
                    ops.createString("config")
            );
        }

        @Override
        public <T> DataResult<ConfiguredGame<?>> decode(DynamicOps<T> ops, MapLike<T> input) {
            DataResult<GameType<?>> typeResult = GameType.REGISTRY.decode(ops, input.get("type")).map(Pair::getFirst);

            return typeResult.flatMap(type -> {
                String name = this.decodeStringOrNull(ops, input.get("name"));
                String translation = this.decodeStringOrNull(ops, input.get("translation"));
                Map<Identifier, Dynamic<?>> custom = this.decodeCustomValues(ops, input.get("custom"));

                Codec<?> configCodec = type.getConfigCodec();
                return this.decodeConfig(ops, input, configCodec).map(config -> {
                    return this.createConfigUnchecked(type, name, translation, custom, config);
                });
            });
        }

        private <T> String decodeStringOrNull(DynamicOps<T> ops, T input) {
            return Codec.STRING.decode(ops, input)
                    .result().map(Pair::getFirst)
                    .orElse(null);
        }

        private <T> Map<Identifier, Dynamic<?>> decodeCustomValues(DynamicOps<T> ops, T input) {
            return CUSTOM_VALUES_CODEC.decode(ops, input)
                    .result().map(Pair::getFirst)
                    .orElse(ImmutableMap.of());
        }

        private <T> DataResult<?> decodeConfig(DynamicOps<T> ops, MapLike<T> input, Codec<?> configCodec) {
            if (configCodec instanceof MapCodec.MapCodecCodec<?>) {
                return ((MapCodecCodec<?>) configCodec).codec().decode(ops, input).map(Function.identity());
            } else {
                return configCodec.decode(ops, input.get("config")).map(Pair::getFirst);
            }
        }

        @SuppressWarnings("unchecked")
        private <C> ConfiguredGame<C> createConfigUnchecked(GameType<C> type, String name, String translation, Map<Identifier, Dynamic<?>> custom, Object config) {
            return new ConfiguredGame<>(this.source, type, name, translation, custom, (C) config);
        }

        @Override
        public <T> RecordBuilder<T> encode(ConfiguredGame<?> game, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            return this.encodeUnchecked(game, ops, prefix);
        }

        private <T, C> RecordBuilder<T> encodeUnchecked(ConfiguredGame<C> game, DynamicOps<T> ops, RecordBuilder<T> prefix) {
            Codec<C> codec = game.type.getConfigCodec();
            if (codec instanceof MapCodecCodec<?>) {
                prefix = ((MapCodecCodec<C>) codec).codec().encode(game.config, ops, prefix);
            } else {
                prefix.add("config", codec.encodeStart(ops, game.config));
            }

            prefix.add("type", GameType.REGISTRY.encodeStart(ops, game.type));

            if (game.name != null) {
                prefix.add("name", Codec.STRING.encodeStart(ops, game.name));
            }

            if (game.translation != null) {
                prefix.add("translation", Codec.STRING.encodeStart(ops, game.translation));
            }

            if (!game.custom.isEmpty()) {
                prefix.add("custom", CUSTOM_VALUES_CODEC.encodeStart(ops, game.custom));
            }

            return prefix;
        }
    }
}
