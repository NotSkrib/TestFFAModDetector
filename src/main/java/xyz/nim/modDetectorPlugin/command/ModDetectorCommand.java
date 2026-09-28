package xyz.nim.modDetectorPlugin.command;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.nim.modDetectorPlugin.ModDetectorPlugin;
import xyz.nim.modDetectorPlugin.Msg;
import xyz.nim.modDetectorPlugin.catalog.ModCatalog;

public final class ModDetectorCommand
implements CommandExecutor,
TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("help", "status", "hacks", "check", "history", "list", "allow", "disallow", "detect", "ignore", "reload");
    private static final List<Entry> MENU = List.of(new Entry("status", "Plugin, catalog, and enforcement status", false), new Entry("check <player>", "Run a full check on a player right now", true), new Entry("history <player>", "Last detection result recorded for a player", true), new Entry("list [category] [all|ticked|unticked]", "Browse the mod database by category", true), new Entry("allow <mod>", "Punish axis: stop kicking for a mod (still detected/alerted)", true), new Entry("disallow <mod>", "Punish axis: make a mod grounds for a kick again", true), new Entry("detect <mod>", "Detect axis: TICK a mod on - add it to the detect: list (tier 1)", true), new Entry("ignore <mod>", "Detect axis: UNTICK a mod - only tier 2 escalation covers it", true), new Entry("hacks", "Sign-probe definitions enabled / total", false), new Entry("reload", "Reload config.yml (settings + mod database)", false));
    private final ModDetectorPlugin plugin;

    public ModDetectorCommand(ModDetectorPlugin modDetectorPlugin) {
        this.plugin = modDetectorPlugin;
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        if (stringArray.length == 0) {
            this.help(commandSender);
            return true;
        }
        switch (stringArray[0].toLowerCase()) {
            case "help": {
                this.help(commandSender);
                break;
            }
            case "status": {
                this.status(commandSender);
                break;
            }
            case "hacks": {
                ModDetectorCommand.stat(commandSender, "Enabled for /moddetector check", this.plugin.enabledHackCount() + "/" + this.plugin.totalHackCount());
                ModDetectorCommand.stat(commandSender, "Tier 1 (detect list)", this.plugin.primaryHackCount() + "/" + this.plugin.totalHackCount());
                ModDetectorCommand.stat(commandSender, "Tier 2 (full catalog)", String.valueOf(this.plugin.totalHackCount()));
                break;
            }
            case "check": {
                if (stringArray.length < 2) {
                    commandSender.sendMessage(Msg.error("Usage: /moddetector check <player>"));
                    return true;
                }
                Player player = Bukkit.getPlayerExact((String)stringArray[1]);
                if (player == null) {
                    commandSender.sendMessage(Msg.error("Player not online."));
                    return true;
                }
                if (!this.plugin.manualCheck(player, commandSender)) break;
                commandSender.sendMessage(Msg.prefixed(((TextComponent)Component.text((String)"Running check on ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)player.getName(), (TextColor)NamedTextColor.WHITE))).append((Component)Component.text((String)" - results will be logged / alerted when done.", (TextColor)NamedTextColor.GRAY))));
                break;
            }
            case "history": {
                this.history(commandSender, stringArray);
                break;
            }
            case "list": {
                this.list(commandSender, stringArray);
                break;
            }
            case "allow": {
                this.setPunish(commandSender, stringArray, false);
                break;
            }
            case "disallow": {
                this.setPunish(commandSender, stringArray, true);
                break;
            }
            case "detect": {
                this.setDetect(commandSender, stringArray, true);
                break;
            }
            case "ignore": {
                this.setDetect(commandSender, stringArray, false);
                break;
            }
            case "reload": {
                this.plugin.reloadAll();
                commandSender.sendMessage(Msg.prefixed((Component)Component.text((String)"Config + catalog reloaded.", (TextColor)NamedTextColor.GREEN)));
                break;
            }
            default: {
                this.help(commandSender);
            }
        }
        return true;
    }

    private static Component bool(boolean bl, String string, String string2) {
        return bl ? Component.text((String)("\u2714 " + string), (TextColor)NamedTextColor.GREEN) : Component.text((String)("\u2716 " + string2), (TextColor)NamedTextColor.RED);
    }

    private static Component statRow(String string, Component component) {
        return Component.text((String)(" " + string + ": "), (TextColor)NamedTextColor.GRAY).append(component);
    }

    private static void detectLine(CommandSender commandSender, String display, String suffix, TextColor color) {
        commandSender.sendMessage(Msg.prefixed(Component.text((String)display, (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)(" " + suffix), (TextColor)color))));
    }

    private static void stat(CommandSender commandSender, String label, String value) {
        commandSender.sendMessage(Msg.prefixed(Component.text((String)(label + ": "), (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)value, (TextColor)NamedTextColor.YELLOW))));
    }

    private void help(CommandSender commandSender) {
        commandSender.sendMessage(Msg.divider());
        commandSender.sendMessage((Component)Component.text((String)("  " + Msg.BRAND), (TextColor)Msg.ACCENT, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
        commandSender.sendMessage(Msg.divider());
        for (Entry entry : MENU) {
            Component component = ((TextComponent)Component.text((String)(" /moddetector " + entry.usage()), (TextColor)NamedTextColor.YELLOW).hoverEvent((HoverEventSource)HoverEvent.showText((Component)Component.text((String)entry.description(), (TextColor)NamedTextColor.GRAY).append((Component)(entry.needsArg() ? Component.text((String)"\nClick to fill in the command.", (TextColor)NamedTextColor.DARK_GRAY) : Component.text((String)"\nClick to fill in, then press enter.", (TextColor)NamedTextColor.DARK_GRAY)))))).clickEvent(ClickEvent.suggestCommand((String)("/moddetector " + entry.usage().split(" ")[0] + (entry.needsArg() ? " " : ""))));
            commandSender.sendMessage(component);
        }
        commandSender.sendMessage(Msg.divider());
    }

    private void status(CommandSender commandSender) {
        commandSender.sendMessage(Msg.divider());
        commandSender.sendMessage((Component)Component.text((String)("  " + Msg.BRAND + " status"), (TextColor)Msg.ACCENT, (TextDecoration[])new TextDecoration[]{TextDecoration.BOLD}));
        commandSender.sendMessage(Msg.divider());
        commandSender.sendMessage(ModDetectorCommand.statRow("Detect list", Component.text((String)(this.plugin.catalog().tickedCount() + "/" + this.plugin.catalog().knownCount()), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)" ticked", (TextColor)NamedTextColor.DARK_GRAY))));
        commandSender.sendMessage(ModDetectorCommand.statRow("Tier 2 probes", (Component)Component.text((String)(this.plugin.totalHackCount() + " definitions"), (TextColor)NamedTextColor.WHITE)).append((Component)Component.text((String)(" (tier 1: " + this.plugin.primaryHackCount() + ")"), (TextColor)NamedTextColor.DARK_GRAY)));
        commandSender.sendMessage(ModDetectorCommand.statRow("Escalation", ModDetectorCommand.bool(this.plugin.escalateOnDetection(), "on - tier 1 hit runs the full catalog", "off - tier 1 only")));
        commandSender.sendMessage(ModDetectorCommand.statRow("Sign-probe definitions", (Component)Component.text((String)(this.plugin.enabledHackCount() + "/" + this.plugin.totalHackCount()), (TextColor)NamedTextColor.WHITE)));
        commandSender.sendMessage(ModDetectorCommand.statRow("Sign-probe", ModDetectorCommand.bool(this.plugin.signProbeActive(), "active", "disabled")));
        commandSender.sendMessage(ModDetectorCommand.statRow("Kick enforcement", ModDetectorCommand.bool(this.plugin.kickEnabled(), "enabled", "alert-only")));
        commandSender.sendMessage(Msg.divider());
        commandSender.sendMessage(((TextComponent)Component.text((String)" /moddetector help", (TextColor)NamedTextColor.DARK_GRAY, (TextDecoration[])new TextDecoration[]{TextDecoration.ITALIC}).clickEvent(ClickEvent.suggestCommand((String)"/moddetector help"))).hoverEvent((HoverEventSource)HoverEvent.showText((Component)Component.text((String)"See all commands", (TextColor)NamedTextColor.GRAY))));
    }

    private void history(CommandSender commandSender, String[] stringArray) {
        if (stringArray.length < 2) {
            commandSender.sendMessage(Msg.error("Usage: /moddetector history <player>"));
            return;
        }
        UUID uUID = this.plugin.resolveHistoryUuid(stringArray[1]);
        if (uUID == null) {
            commandSender.sendMessage(Msg.error("'" + stringArray[1] + "' isn't online and has no recorded history.").append((Component)Component.text((String)" (only ever checked/online-since-last-reload players have any.)", (TextColor)NamedTextColor.DARK_GRAY)));
            return;
        }
        String string = Bukkit.getOfflinePlayer((UUID)uUID).getName();
        TextComponent textComponent = Component.text((String)(string != null ? string : stringArray[1]), (TextColor)NamedTextColor.WHITE);
        Set<String> set = this.plugin.lastResultFor(uUID);
        if (set == null) {
            commandSender.sendMessage(Msg.prefixed(((TextComponent)Component.text((String)"No detection record for ", (TextColor)NamedTextColor.GRAY).append((Component)textComponent)).append((Component)Component.text((String)" since their last join/reload.", (TextColor)NamedTextColor.GRAY))));
        } else if (set.isEmpty()) {
            commandSender.sendMessage(Msg.prefixed(((TextComponent)textComponent.append((Component)Component.text((String)" - last check: ", (TextColor)NamedTextColor.GRAY))).append((Component)Component.text((String)"clean", (TextColor)NamedTextColor.GREEN))));
        } else {
            String string2 = set.stream().map(this.plugin::displayFor).distinct().collect(Collectors.joining(", "));
            commandSender.sendMessage(Msg.prefixed(((TextComponent)textComponent.append((Component)Component.text((String)" - last detected: ", (TextColor)NamedTextColor.RED))).append((Component)Component.text((String)string2, (TextColor)NamedTextColor.YELLOW))));
        }
    }

    private void setPunish(CommandSender commandSender, String[] stringArray, boolean bl) {
        String string = bl ? "disallow" : "allow";
        String string2 = string;
        if (stringArray.length < 2) {
            commandSender.sendMessage(Msg.error("Usage: /moddetector " + string + " <mod-id>"));
            return;
        }
        String string3 = stringArray[1].toLowerCase();
        if (!this.plugin.catalog().knows(string3)) {
            commandSender.sendMessage(Msg.error("Unknown mod id '" + string3 + "'.").append((Component)Component.text((String)" Hover an entry in ", (TextColor)NamedTextColor.GRAY)).append((Component)Component.text((String)"/moddetector list <category>", (TextColor)NamedTextColor.YELLOW)).append((Component)Component.text((String)" to see its id.", (TextColor)NamedTextColor.GRAY)));
            return;
        }
        ModDetectorPlugin.PunishEditResult punishEditResult = this.plugin.setPunish(string3, bl);
        switch (punishEditResult) {
            case OK: {
                String string4 = this.plugin.displayFor(string3);
                TextComponent textComponent = bl ? Component.text((String)"is grounds for a kick again.", (TextColor)NamedTextColor.RED) : Component.text((String)"will no longer be kicked for (still logged/alerted).", (TextColor)NamedTextColor.GREEN);
                commandSender.sendMessage(Msg.prefixed(((TextComponent)Component.text((String)string4, (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)" ", (TextColor)NamedTextColor.GRAY))).append((Component)textComponent)));
                break;
            }
            case NOT_FOUND: {
                commandSender.sendMessage(Msg.error("Couldn't find '" + string3 + "' in config.yml's mods: catalog to edit."));
                break;
            }
            case IO_ERROR: {
                commandSender.sendMessage(Msg.error("Failed to read/write config.yml - check the console for details."));
            }
        }
    }

    // The detect axis, kept deliberately distinct from the punish axis above: detect/ignore answers
    // "is this mod looked for at all?", allow/disallow answers "does finding it kick?". They are
    // independent, and a ticked-but-allowed mod is a perfectly normal combination.
    private void setDetect(CommandSender commandSender, String[] stringArray, boolean ticked) {
        String string = ticked ? "detect" : "ignore";
        if (stringArray.length < 2) {
            commandSender.sendMessage(Msg.error("Usage: /moddetector " + string + " <mod-id>"));
            return;
        }
        String string2 = stringArray[1].toLowerCase();
        if (!this.plugin.knownSignalIds().contains(string2)) {
            commandSender.sendMessage(Msg.error("Unknown mod id '" + string2 + "'.").append((Component)Component.text((String)" Hover an entry in ", (TextColor)NamedTextColor.GRAY)).append((Component)Component.text((String)"/moddetector list <category>", (TextColor)NamedTextColor.YELLOW)).append((Component)Component.text((String)" to see its id.", (TextColor)NamedTextColor.GRAY)));
            return;
        }
        switch (this.plugin.setTicked(string2, ticked)) {
            case OK: {
                ModDetectorCommand.detectLine(commandSender, this.plugin.displayFor(string2), ticked ? "is ticked - probed in tier 1." : "is unticked - only tier 2 escalation covers it.", ticked ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
                break;
            }
            case ALREADY_TICKED: {
                ModDetectorCommand.detectLine(commandSender, this.plugin.displayFor(string2), "is already in the detect: list.", NamedTextColor.GRAY);
                break;
            }
            case NOT_TICKED: {
                ModDetectorCommand.detectLine(commandSender, this.plugin.displayFor(string2), "isn't in the detect: list.", NamedTextColor.GRAY);
                break;
            }
            case UNSUPPORTED_SHAPE: {
                commandSender.sendMessage(Msg.error("The detect: list in config.yml isn't in the block form this command edits. Use the detect: key with one \"- id\" per line (see ADDING-MODS.md)."));
                break;
            }
            case NOT_FOUND: {
                commandSender.sendMessage(Msg.error("Couldn't find a detect: list in config.yml to edit. Keep the bundled structure - see ADDING-MODS.md."));
                break;
            }
            case IO_ERROR: {
                commandSender.sendMessage(Msg.error("Failed to read/write config.yml - check the console for details."));
            }
        }
    }

    private void list(CommandSender commandSender, String[] stringArray) {
        ModCatalog.TickFilter tickFilter = ModCatalog.TickFilter.ALL;
        String string = null;
        if (stringArray.length >= 3) {
            tickFilter = switch (stringArray[2].toLowerCase()) {
                case "ticked", "tick", "on" -> ModCatalog.TickFilter.TICKED;
                case "unticked", "untick", "off" -> ModCatalog.TickFilter.UNTICKED;
                case "all" -> ModCatalog.TickFilter.ALL;
                default -> {
                    commandSender.sendMessage(Msg.error("Filter must be one of: all, ticked, unticked"));
                    yield null;
                }
            };
            if (tickFilter == null) {
                return;
            }
        }
        if (stringArray.length >= 2) {
            string = stringArray[1];
        }
        if (string == null) {
            commandSender.sendMessage(Msg.prefixed(Component.text((String)"Mod database, by category ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)"(/moddetector list <category> [all|ticked|unticked])", (TextColor)NamedTextColor.DARK_GRAY))));
            for (Map.Entry<ModCatalog.Category, Integer> entry : this.plugin.catalog().countsByCategory(ModCatalog.TickFilter.ALL).entrySet()) {
                int n = this.plugin.catalog().countsByCategory(ModCatalog.TickFilter.TICKED).getOrDefault(entry.getKey(), 0);
                Component component = ((TextComponent)Component.text((String)(" " + entry.getKey().name()), (TextColor)NamedTextColor.YELLOW).clickEvent(ClickEvent.suggestCommand((String)("/moddetector list " + entry.getKey().name().toLowerCase())))).hoverEvent((HoverEventSource)HoverEvent.showText((Component)Component.text((String)("Click to browse " + entry.getKey().name()), (TextColor)NamedTextColor.GRAY)));
                commandSender.sendMessage(component.append((Component)Component.text((String)(": " + String.valueOf(entry.getValue())), (TextColor)NamedTextColor.WHITE)).append((Component)Component.text((String)(" (" + n + " ticked)"), (TextColor)NamedTextColor.DARK_GRAY)));
            }
            return;
        }
        List<ModCatalog.ModDef> list = this.plugin.catalog().listMods(string, tickFilter);
        if (list.isEmpty()) {
            commandSender.sendMessage(Msg.prefixed((Component)Component.text((String)("No " + ModDetectorCommand.filterName(tickFilter) + " mods in category '" + string + "'."), (TextColor)NamedTextColor.GRAY)));
            return;
        }
        commandSender.sendMessage(Msg.prefixed((Component)Component.text((String)(list.size() + " " + ModDetectorCommand.filterName(tickFilter) + " mod(s) in " + string.toUpperCase() + ":"), (TextColor)NamedTextColor.GRAY)));
        TextComponent textComponent = Component.empty();
        for (int i = 0; i < list.size(); ++i) {
            ModCatalog.ModDef modDef = list.get(i);
            boolean ticked2 = this.plugin.catalog().isTicked(modDef.id());
            Component component = ModDetectorCommand.bool(ticked2, modDef.display(), modDef.display())
                    .hoverEvent((HoverEventSource)HoverEvent.showText(ModDetectorCommand.modHover(modDef, ticked2)));
            textComponent = (TextComponent)textComponent.append(component);
            if (i >= list.size() - 1) continue;
            textComponent = (TextComponent)textComponent.append((Component)Component.text((String)", ", (TextColor)NamedTextColor.DARK_GRAY));
        }
        commandSender.sendMessage((Component)textComponent);
    }

    // Hover carries the detail (id, both axes, which tier it would be probed in); the visible
    // marker carries the answer, because hover does not exist in the console or in a log paste.
    private static Component modHover(ModCatalog.ModDef modDef, boolean ticked) {
        TextColor tickColor = ticked ? NamedTextColor.GREEN : NamedTextColor.RED;
        TextColor punishColor = modDef.punish() ? NamedTextColor.RED : NamedTextColor.GREEN;
        String tier = ticked ? "probed every join (tier 1)" : "probed only after a tier-1 hit (tier 2)";
        Component component = Component.text((String)modDef.id(), (TextColor)NamedTextColor.DARK_GRAY);
        component = component.append((Component)Component.text((String)("\nticked: " + ticked), tickColor));
        component = component.append((Component)Component.text((String)("\npunish: " + modDef.punish()), punishColor));
        return component.append((Component)Component.text((String)("\n" + tier), (TextColor)NamedTextColor.DARK_GRAY));
    }

    private static String filterName(ModCatalog.TickFilter tickFilter) {
        return switch (tickFilter) {
            case ALL -> "known";
            case TICKED -> "ticked";
            case UNTICKED -> "unticked";
        };
    }

    public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
        if (stringArray.length == 1) {
            return ModDetectorCommand.prefixMatch(SUBCOMMANDS, stringArray[0]);
        }
        if (stringArray.length == 3 && "list".equalsIgnoreCase(stringArray[0])) {
            return ModDetectorCommand.prefixMatch(List.of("all", "ticked", "unticked"), stringArray[2]);
        }
        if (stringArray.length == 2) {
            return switch (stringArray[0].toLowerCase()) {
                case "check" -> ModDetectorCommand.prefixMatch(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), stringArray[1]);
                case "history" -> {
                    LinkedHashSet<String> var7_7 = new LinkedHashSet<String>();
                    Bukkit.getOnlinePlayers().forEach(player -> var7_7.add(player.getName()));
                    var7_7.addAll(this.plugin.knownHistoryNames());
                    yield ModDetectorCommand.prefixMatch(new ArrayList<String>(var7_7), stringArray[1]);
                }
                case "list" -> ModDetectorCommand.prefixMatch(List.of("cheat", "suspicious", "launcher", "utility", "unknown"), stringArray[1]);
                case "allow", "disallow" -> ModDetectorCommand.prefixMatch(this.plugin.catalog().allIds(), stringArray[1]);
                case "detect", "ignore" -> ModDetectorCommand.prefixMatch(new ArrayList<String>(this.plugin.knownSignalIds()), stringArray[1]);
                default -> List.of();
            };
        }
        return List.of();
    }

    private static List<String> prefixMatch(List<String> list, String string) {
        String string2 = string.toLowerCase();
        ArrayList<String> arrayList = new ArrayList<String>();
        for (String string3 : list) {
            if (!string3.toLowerCase().startsWith(string2)) continue;
            arrayList.add(string3);
        }
        return arrayList;
    }

    private record Entry(String usage, String description, boolean needsArg) {
    }
}


