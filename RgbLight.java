package com.rgblight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * RGB Light v0.1 - серверный мод. Только оператор управляет светами, всё хранится на сервере
 * (config/rgblight.json). Ванильный свет и блоки не трогаются: свет рисуется частицами.
 */
public class RgbLight implements ModInitializer {

    public static final String MOD_ID = "rgblight";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // ---------- данные ----------

    public static class LightData {
        public String name = "";
        public String type = "point";   // "point" или "spot"
        public String dim = "";          // измерение (для света в точке)
        public double x, y, z;           // позиция
        public double tx, ty, tz;        // куда светит прожектор (для света в точке)
        public String entity = null;     // UUID существа, за которым следует свет
        public int r = 255, g = 255, b = 255;
        public double radius = 5.0;      // размер облака (point)
        public double range = 12.0;      // длина луча (spot)
        public double angle = 20.0;      // угол луча в градусах (spot)
        public boolean enabled = true;

        int rgb() {
            return (r << 16) | (g << 8) | b;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<LightData> LIGHTS = new ArrayList<>();
    private static int ticks = 0;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("rgblight.json");
    }

    private static void load() {
        LIGHTS.clear();
        try {
            Path f = file();
            if (Files.exists(f)) {
                List<LightData> list = GSON.fromJson(Files.readString(f), new TypeToken<List<LightData>>() {}.getType());
                if (list != null) {
                    LIGHTS.addAll(list);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Не удалось прочитать rgblight.json", e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file(), GSON.toJson(LIGHTS));
        } catch (Exception e) {
            LOGGER.error("Не удалось сохранить rgblight.json", e);
        }
    }

    private static LightData find(String name) {
        for (LightData l : LIGHTS) {
            if (l.name.equalsIgnoreCase(name)) {
                return l;
            }
        }
        return null;
    }

    // ---------- запуск ----------

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> load());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
        ServerTickEvents.END_SERVER_TICK.register(RgbLight::tick);
    }

    // ---------- отрисовка (частицы) ----------

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % 2 != 0) {
            return;
        }
        for (LightData l : LIGHTS) {
            if (l.enabled) {
                try {
                    render(server, l);
                } catch (Exception e) {
                    LOGGER.error("Ошибка отрисовки света " + l.name, e);
                }
            }
        }
    }

    private static void render(MinecraftServer server, LightData l) {
        ServerLevel level = null;
        Vec3 pos;
        Vec3 dir = null;
        boolean spot = l.type.equals("spot");

        if (l.entity != null) {
            UUID id;
            try {
                id = UUID.fromString(l.entity);
            } catch (IllegalArgumentException ex) {
                return;
            }
            Entity e = null;
            for (ServerLevel sl : server.getAllLevels()) {
                e = sl.getEntity(id);
                if (e != null) {
                    level = sl;
                    break;
                }
            }
            if (e == null || level == null) {
                return;
            }
            if (spot) {
                pos = e.getEyePosition();
                dir = e.getLookAngle();
            } else {
                pos = new Vec3(e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ());
            }
        } else {
            for (ServerLevel sl : server.getAllLevels()) {
                if (sl.dimension().toString().equals(l.dim)) {
                    level = sl;
                    break;
                }
            }
            if (level == null) {
                return;
            }
            pos = new Vec3(l.x, l.y, l.z);
            if (spot) {
                Vec3 d = new Vec3(l.tx - l.x, l.ty - l.y, l.tz - l.z);
                if (d.length() < 0.001) {
                    return;
                }
                dir = d.normalize();
            }
        }

        if (spot) {
            DustParticleOptions dust = new DustParticleOptions(l.rgb(), 1.2f);
            double half = Math.toRadians(l.angle);
            int steps = 8;
            for (int i = 1; i <= steps; i++) {
                double d = l.range * i / steps;
                double spread = Math.tan(half) * d * 0.5;
                Vec3 p = pos.add(dir.scale(d));
                level.sendParticles(dust, p.x, p.y, p.z, 2, spread, spread, spread, 0.0);
            }
        } else if (ticks % 4 == 0) {
            float size = (float) Math.min(4.0, 0.8 + l.radius * 0.25);
            DustParticleOptions dust = new DustParticleOptions(l.rgb(), size);
            int count = (int) Math.max(3, l.radius * 3);
            double spread = l.radius * 0.45;
            level.sendParticles(dust, pos.x, pos.y, pos.z, count, spread, spread, spread, 0.0);
        }
    }

    // ---------- команды ----------

    private static boolean isOp(CommandSourceStack s) {
        return s.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
    }

    private static final SuggestionProvider<CommandSourceStack> NAMES = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        for (LightData l : LIGHTS) {
            names.add(l.name);
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> rgb(
            Command<CommandSourceStack> run, ArgumentBuilder<CommandSourceStack, ?> next) {
        RequiredArgumentBuilder<CommandSourceStack, Integer> b =
                Commands.argument("b", IntegerArgumentType.integer(0, 255)).executes(run);
        if (next != null) {
            b.then(next);
        }
        RequiredArgumentBuilder<CommandSourceStack, Integer> g =
                Commands.argument("g", IntegerArgumentType.integer(0, 255)).then(b);
        return Commands.argument("r", IntegerArgumentType.integer(0, 255)).then(g);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rgblight")
                .requires(RgbLight::isOp)
                // /rgblight add point <имя> <поз> <r> <g> <b> [радиус]
                .then(Commands.literal("add")
                        .then(Commands.literal("point")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(rgb(c -> addPoint(c),
                                                        Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
                                                                .executes(c -> addPoint(c)))))))
                        // /rgblight add spot <имя> <откуда> <куда> <r> <g> <b> [длина [угол]]
                        .then(Commands.literal("spot")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(Commands.argument("to", Vec3Argument.vec3())
                                                        .then(rgb(c -> addSpot(c),
                                                                Commands.argument("range", DoubleArgumentType.doubleArg(1, 128))
                                                                        .executes(c -> addSpot(c))
                                                                        .then(Commands.argument("angle", DoubleArgumentType.doubleArg(1, 80))
                                                                                .executes(c -> addSpot(c))))))))))
                // /rgblight attach point <имя> <существо> <r> <g> <b> [радиус]
                .then(Commands.literal("attach")
                        .then(Commands.literal("point")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("target", EntityArgument.entity())
                                                .then(rgb(c -> attach(c, "point"),
                                                        Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 64))
                                                                .executes(c -> attach(c, "point")))))))
                        // /rgblight attach spot <имя> <существо> <r> <g> <b> [длина [угол]]
                        .then(Commands.literal("spot")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("target", EntityArgument.entity())
                                                .then(rgb(c -> attach(c, "spot"),
                                                        Commands.argument("range", DoubleArgumentType.doubleArg(1, 128))
                                                                .executes(c -> attach(c, "spot"))
                                                                .then(Commands.argument("angle", DoubleArgumentType.doubleArg(1, 80))
                                                                        .executes(c -> attach(c, "spot")))))))))
                // /rgblight color <имя> <r> <g> <b>
                .then(Commands.literal("color")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .then(rgb(c -> setColor(c), null))))
                // /rgblight size <имя> <значение>  (радиус для point, длина луча для spot)
                .then(Commands.literal("size")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.5, 128))
                                        .executes(c -> setSize(c)))))
                .then(Commands.literal("toggle")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .executes(c -> toggle(c))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word()).suggests(NAMES)
                                .executes(c -> remove(c))))
                .then(Commands.literal("list").executes(c -> list(c)))
                .then(Commands.literal("clear").executes(c -> clear(c))));
    }

    private static double optDouble(CommandContext<CommandSourceStack> c, String name, double def) {
        try {
            return DoubleArgumentType.getDouble(c, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static LightData base(CommandContext<CommandSourceStack> c, String type) {
        LightData l = new LightData();
        l.name = StringArgumentType.getString(c, "name");
        l.type = type;
        l.r = IntegerArgumentType.getInteger(c, "r");
        l.g = IntegerArgumentType.getInteger(c, "g");
        l.b = IntegerArgumentType.getInteger(c, "b");
        return l;
    }

    private static int store(CommandContext<CommandSourceStack> c, LightData l) {
        LIGHTS.removeIf(o -> o.name.equalsIgnoreCase(l.name));
        LIGHTS.add(l);
        save();
        c.getSource().sendSuccess(() -> Component.literal("Свет «" + l.name + "» (" + l.type + ") создан"), false);
        return 1;
    }

    private static int addPoint(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        LightData l = base(c, "point");
        Vec3 p = Vec3Argument.getVec3(c, "pos");
        l.x = p.x;
        l.y = p.y;
        l.z = p.z;
        l.dim = c.getSource().getLevel().dimension().toString();
        l.radius = optDouble(c, "radius", 5.0);
        return store(c, l);
    }

    private static int addSpot(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        LightData l = base(c, "spot");
        Vec3 p = Vec3Argument.getVec3(c, "pos");
        Vec3 t = Vec3Argument.getVec3(c, "to");
        l.x = p.x;
        l.y = p.y;
        l.z = p.z;
        l.tx = t.x;
        l.ty = t.y;
        l.tz = t.z;
        l.dim = c.getSource().getLevel().dimension().toString();
        l.range = optDouble(c, "range", 12.0);
        l.angle = optDouble(c, "angle", 20.0);
        return store(c, l);
    }

    private static int attach(CommandContext<CommandSourceStack> c, String type) throws CommandSyntaxException {
        LightData l = base(c, type);
        Entity e = EntityArgument.getEntity(c, "target");
        l.entity = e.getUUID().toString();
        l.radius = optDouble(c, "radius", 5.0);
        l.range = optDouble(c, "range", 12.0);
        l.angle = optDouble(c, "angle", 20.0);
        return store(c, l);
    }

    private static int setColor(CommandContext<CommandSourceStack> c) {
        LightData l = find(StringArgumentType.getString(c, "name"));
        if (l == null) {
            c.getSource().sendFailure(Component.literal("Такого света нет"));
            return 0;
        }
        l.r = IntegerArgumentType.getInteger(c, "r");
        l.g = IntegerArgumentType.getInteger(c, "g");
        l.b = IntegerArgumentType.getInteger(c, "b");
        save();
        c.getSource().sendSuccess(() -> Component.literal("Цвет света «" + l.name + "» изменён"), false);
        return 1;
    }

    private static int setSize(CommandContext<CommandSourceStack> c) {
        LightData l = find(StringArgumentType.getString(c, "name"));
        if (l == null) {
            c.getSource().sendFailure(Component.literal("Такого света нет"));
            return 0;
        }
        double v = DoubleArgumentType.getDouble(c, "value");
        if (l.type.equals("spot")) {
            l.range = v;
        } else {
            l.radius = v;
        }
        save();
        c.getSource().sendSuccess(() -> Component.literal("Размер света «" + l.name + "» изменён"), false);
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> c) {
        LightData l = find(StringArgumentType.getString(c, "name"));
        if (l == null) {
            c.getSource().sendFailure(Component.literal("Такого света нет"));
            return 0;
        }
        l.enabled = !l.enabled;
        save();
        c.getSource().sendSuccess(() -> Component.literal("Свет «" + l.name + "»: " + (l.enabled ? "включён" : "выключен")), false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        boolean removed = LIGHTS.removeIf(o -> o.name.equalsIgnoreCase(name));
        if (!removed) {
            c.getSource().sendFailure(Component.literal("Такого света нет"));
            return 0;
        }
        save();
        c.getSource().sendSuccess(() -> Component.literal("Свет «" + name + "» удалён"), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        if (LIGHTS.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("Светов нет"), false);
            return 0;
        }
        for (LightData l : LIGHTS) {
            String line = l.name + " [" + l.type + "] rgb(" + l.r + "," + l.g + "," + l.b + ")"
                    + (l.entity != null ? " за существом" : "") + (l.enabled ? "" : " (выкл)");
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return LIGHTS.size();
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        int n = LIGHTS.size();
        LIGHTS.clear();
        save();
        c.getSource().sendSuccess(() -> Component.literal("Удалено светов: " + n), false);
        return n;
    }
}
