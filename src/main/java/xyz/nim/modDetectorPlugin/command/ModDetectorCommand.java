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
    private static final List<String> SUBCOMMANDS = List.of("help", "status", "hacks", "check", "history", "list", "allow", "disallow", "reload");
    private static final List<Entry> MENU = List.of(new Entry("status", "Plugin, catalog, and enforcement status", false), new Entry("check <player>", "Run a full check on a player right now", true), new Entry("history <player>", "Last detection result recorded for a player", true), new Entry("list [category]", "Browse the mod database by category", true), new Entry("allow <mod>", "Stop kicking for a mod (still logged/alerted)", true), new Entry("disallow <mod>", "Make a mod grounds for a kick again", true), new Entry("hacks", "Sign-probe definitions enabled / total", false), new Entry("reload", "Reload config.yml (settings + mod database)", false));
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
                commandSender.sendMessage(Msg.prefixed(Component.text((String)"Enabled for /moddetector check: ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)(this.plugin.enabledHackCount() + "/" + this.plugin.totalHackCount()), (TextColor)NamedTextColor.YELLOW))));
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
        commandSender.sendMessage(ModDetectorCommand.statRow("Known mods", Component.text((int)this.plugin.catalog().knownCount(), (TextColor)NamedTextColor.WHITE).append((Component)Component.text((String)(" (" + this.plugin.catalog().trackedCount() + " tracked)"), (TextColor)NamedTextColor.DARK_GRAY))));
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

    private void list(CommandSender commandSender, String[] stringArray) {
        if (stringArray.length < 2) {
            commandSender.sendMessage(Msg.prefixed(Component.text((String)"Mod database, by category ", (TextColor)NamedTextColor.GRAY).append((Component)Component.text((String)"(/moddetector list <category>)", (TextColor)NamedTextColor.DARK_GRAY))));
            for (Map.Entry<ModCatalog.Category, Integer> entry : this.plugin.catalog().countsByCategory().entrySet()) {
                Component component = ((TextComponent)Component.text((String)(" " + entry.getKey().name()), (TextColor)NamedTextColor.YELLOW).clickEvent(ClickEvent.suggestCommand((String)("/moddetector list " + entry.getKey().name().toLowerCase())))).hoverEvent((HoverEventSource)HoverEvent.showText((Component)Component.text((String)("Click to browse " + entry.getKey().name()), (TextColor)NamedTextColor.GRAY)));
                commandSender.sendMessage(component.append((Component)Component.text((String)(": " + String.valueOf(entry.getValue())), (TextColor)NamedTextColor.WHITE)));
            }
            return;
        }
        List<ModCatalog.ModDef> list = this.plugin.catalog().listMods(stringArray[1]);
        if (list.isEmpty()) {
            commandSender.sendMessage(Msg.prefixed((Component)Component.text((String)("No tracked mods in category '" + stringArray[1] + "'."), (TextColor)NamedTextColor.GRAY)));
            return;
        }
        commandSender.sendMessage(Msg.prefixed((Component)Component.text((String)(list.size() + " tracked mod(s) in " + stringArray[1].toUpperCase() + ":"), (TextColor)NamedTextColor.GRAY)));
        TextComponent textComponent = Component.empty();
        for (int i = 0; i < list.size(); ++i) {
            ModCatalog.ModDef modDef = list.get(i);
            Component component = Component.text((String)modDef.display(), (TextColor)NamedTextColor.YELLOW).hoverEvent((HoverEventSource)HoverEvent.showText((Component)Component.text((String)modDef.id(), (TextColor)NamedTextColor.DARK_GRAY).append((Component)Component.text((String)("\npunish: " + modDef.punish()), (TextColor)(modDef.punish() ? NamedTextColor.RED : NamedTextColor.GREEN)))));
            textComponent = (TextComponent)textComponent.append(component);
            if (i >= list.size() - 1) continue;
            textComponent = (TextComponent)textComponent.append((Component)Component.text((String)", ", (TextColor)NamedTextColor.DARK_GRAY));
        }
        commandSender.sendMessage((Component)textComponent);
    }

    public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
        if (stringArray.length == 1) {
            return ModDetectorCommand.prefixMatch(SUBCOMMANDS, stringArray[0]);
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


