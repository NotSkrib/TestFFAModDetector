package xyz.nim.modDetectorPlugin.catalog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import xyz.nim.modDetectorPlugin.hackcheck.HackDefinition;

public final class ModCatalog {
    private Map<String, ModDef> known = new HashMap<String, ModDef>();
    private Set<String> blockedMods = new HashSet<String>();
    private boolean whitelistMode = false;

    public void load(ConfigurationSection configurationSection, ConfigurationSection configurationSection2, List<String> list, boolean bl) {
        HashMap<String, ModDef> hashMap = new HashMap<String, ModDef>();
        this.loadSection(configurationSection, hashMap);
        this.loadSection(configurationSection2, hashMap);
        this.known = hashMap;
        this.blockedMods = new HashSet<String>(list == null ? List.of() : list);
        this.whitelistMode = bl;
    }

    private void loadSection(ConfigurationSection configurationSection, Map<String, ModDef> map) {
        if (configurationSection == null) {
            return;
        }
        for (String string : configurationSection.getKeys(false)) {
            ConfigurationSection configurationSection2 = configurationSection.getConfigurationSection(string);
            if (configurationSection2 != null) {
                map.put(string, this.parseMod(string, configurationSection2));
                continue;
            }
            Object object = configurationSection.get(string);
            if (!(object instanceof Map)) continue;
            Map map2 = (Map)object;
            map.put(string, this.parseMod(string, map2));
        }
    }

    private ModDef parseMod(String string, ConfigurationSection configurationSection) {
        List list;
        List list2;
        String string2 = configurationSection.getString("display-name", configurationSection.getString("name", configurationSection.getString("display", string)));
        boolean bl = configurationSection.getBoolean("punish", true);
        Category category = ModCatalog.parseCategory(configurationSection.getString("category", "UNKNOWN"));
        List<String> list3 = List.copyOf(configurationSection.getStringList("punishments"));
        if (configurationSection.isList("signals")) {
            ArrayList<Signal> arrayList = new ArrayList<Signal>();
            for (Map map : configurationSection.getMapList("signals")) {
                arrayList.add(this.parseSignal(map));
            }
            return new ModDef(string, string2, category, arrayList, bl, list3);
        }
        ArrayList<Signal> arrayList = new ArrayList<Signal>();
        List list4 = configurationSection.getStringList("channels");
        if (!list4.isEmpty()) {
            arrayList.add(new Signal(SignalSource.CHANNEL, List.copyOf(list4), List.of(), false));
        }
        if (!(list2 = configurationSection.getStringList("brand")).isEmpty()) {
            arrayList.add(new Signal(SignalSource.BRAND, List.copyOf(list2), List.of(), false));
        }
        if (!(list = configurationSection.getStringList("keys")).isEmpty()) {
            arrayList.add(new Signal(SignalSource.TRANSLATE, List.of(), List.copyOf(list), false));
        }
        return new ModDef(string, string2, category, arrayList, bl, list3);
    }

    private ModDef parseMod(String string, Map<?, ?> map) {
        List<String> list;
        List<String> list2;
        ArrayList<Signal> arrayList;
        String string2 = ModCatalog.string(map.get("display-name"), ModCatalog.string(map.get("name"), ModCatalog.string(map.get("display"), string)));
        boolean bl = ModCatalog.toBoolean(map.get("punish"), true);
        Category category = ModCatalog.parseCategory(ModCatalog.string(map.get("category"), "UNKNOWN"));
        List<String> list3 = ModCatalog.listOf(map.get("punishments"));
        Object obj = map.get("signals");
        if (obj instanceof List && !(arrayList = (ArrayList<Signal>)obj).isEmpty()) {
            ArrayList<Signal> arrayList2 = new ArrayList<Signal>();
            for (Object e : arrayList) {
                if (!(e instanceof Map)) continue;
                Map map2 = (Map)e;
                arrayList2.add(this.parseSignal(map2));
            }
            return new ModDef(string, string2, category, arrayList2, bl, list3);
        }
        arrayList = new ArrayList<Signal>();
        List<String> list4 = ModCatalog.listOf(map.get("channels"));
        if (!list4.isEmpty()) {
            arrayList.add(new Signal(SignalSource.CHANNEL, List.copyOf(list4), List.of(), false));
        }
        if (!(list2 = ModCatalog.listOf(map.get("brand"))).isEmpty()) {
            arrayList.add(new Signal(SignalSource.BRAND, List.copyOf(list2), List.of(), false));
        }
        if (!(list = ModCatalog.listOf(map.get("keys"))).isEmpty()) {
            arrayList.add(new Signal(SignalSource.TRANSLATE, List.of(), List.copyOf(list), false));
        }
        return new ModDef(string, string2, category, arrayList, bl, list3);
    }

    private Signal parseSignal(Map<?, ?> map) {
        Object obj = map.get("source");
        SignalSource signalSource = ModCatalog.parseSignalSource(obj == null ? null : obj.toString());
        boolean bl = ModCatalog.toBoolean(map.get("required"), false);
        List<String> list = ModCatalog.listOf(map.get("matches"));
        List<String> list2 = ModCatalog.listOf(map.get("keys"));
        if (signalSource == SignalSource.TRANSLATE && list2.isEmpty() && !list.isEmpty()) {
            list2 = list;
            list = List.of();
        }
        if ((signalSource == SignalSource.BRAND || signalSource == SignalSource.CHANNEL) && list.isEmpty() && !list2.isEmpty()) {
            list = list2;
            list2 = List.of();
        }
        return new Signal(signalSource, List.copyOf(list), List.copyOf(list2), bl);
    }

    private static Category parseCategory(String string) {
        try {
            return Category.valueOf(string == null ? "UNKNOWN" : string.trim().toUpperCase());
        }
        catch (IllegalArgumentException illegalArgumentException) {
            return Category.UNKNOWN;
        }
    }

    private static SignalSource parseSignalSource(String string) {
        try {
            if (string == null) {
                return SignalSource.UNKNOWN;
            }
            return SignalSource.valueOf(string.trim().toUpperCase());
        }
        catch (IllegalArgumentException illegalArgumentException) {
            return SignalSource.UNKNOWN;
        }
    }

    private static boolean toBoolean(Object object, boolean bl) {
        if (object == null) {
            return bl;
        }
        if (object instanceof Boolean) {
            Boolean bl2 = (Boolean)object;
            return bl2;
        }
        return Boolean.parseBoolean(object.toString());
    }

    private static String string(Object object, String string) {
        return object == null ? string : object.toString();
    }

    private static List<String> listOf(Object object) {
        if (!(object instanceof List)) {
            return List.of();
        }
        List list = (List)object;
        ArrayList<String> arrayList = new ArrayList<String>();
        for (Object e : list) {
            if (e == null) continue;
            arrayList.add(e.toString());
        }
        return arrayList;
    }

    public Set<String> detect(Player player) {
        HashSet<String> hashSet = new HashSet<String>();
        ArrayList<String> arrayList = new ArrayList<String>(player.getListeningPluginChannels());
        String string = player.getClientBrandName();
        for (ModDef modDef : this.known.values()) {
            if (!this.isTracked(modDef.id()) || !this.matchesPassiveSignal(modDef, arrayList, string)) continue;
            hashSet.add(modDef.id());
        }
        return hashSet;
    }

    private boolean matchesPassiveSignal(ModDef modDef, List<String> list, String string) {
        for (Signal signal : modDef.signals()) {
            if (!signal.isPassive()) continue;
            if (signal.source() == SignalSource.BRAND) {
                if (string == null || !signal.matches().stream().anyMatch(string2 -> ModCatalog.matchesRegex(string2, string))) continue;
                return true;
            }
            if (signal.source() != SignalSource.CHANNEL) continue;
            for (String string3 : signal.matches()) {
                for (String string4 : list) {
                    if (!ModCatalog.matchesChannel(string3, string4)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    public List<HackDefinition> activeDefinitions() {
        ArrayList<HackDefinition> arrayList = new ArrayList<HackDefinition>();
        for (ModDef modDef : this.known.values()) {
            if (!this.isTracked(modDef.id())) continue;
            for (Signal signal : modDef.signals()) {
                if (!signal.isActive()) continue;
                HackDefinition.Mode mode = switch (signal.source().ordinal()) {
                    case 3 -> HackDefinition.Mode.KEYBIND;
                    case 4 -> HackDefinition.Mode.METEOR;
                    case 2 -> HackDefinition.Mode.TRANSLATE;
                    default -> null;
                };
                if (mode == null) continue;
                List<String> list = signal.keys();
                if (list.isEmpty()) {
                    list = signal.matches();
                }
                for (String string : list) {
                    arrayList.add(new HackDefinition(modDef.id(), modDef.display(), mode, string, modDef.punish(), signal.required()));
                }
            }
        }
        return arrayList;
    }

    private static boolean matchesRegex(String string, String string2) {
        try {
            return Pattern.compile(string, 2).matcher(string2).find();
        }
        catch (PatternSyntaxException patternSyntaxException) {
            return Pattern.compile(Pattern.quote(string), 2).matcher(string2).find();
        }
    }

    private static boolean matchesChannel(String string, String string2) {
        if (string == null || string2 == null) {
            return false;
        }
        String string3 = string.trim();
        if (string3.endsWith("*")) {
            String string4 = string3.substring(0, string3.length() - 1);
            return string2.regionMatches(true, 0, string4, 0, string4.length());
        }
        if (string3.startsWith("^") || string3.contains("[") || string3.contains("(") || string3.contains("|") || string3.contains("\\") || string3.contains(".") || string3.contains("?") || string3.contains("+") || string3.contains("{")) {
            return ModCatalog.matchesRegex(string3, string2);
        }
        return string2.equalsIgnoreCase(string3);
    }

    private boolean isTracked(String string) {
        return this.whitelistMode ? this.blockedMods.contains(string) : !this.blockedMods.contains(string);
    }

    public String displayName(String string) {
        ModDef modDef = this.known.get(string);
        return modDef == null ? string : modDef.display();
    }

    public boolean shouldPunish(String string) {
        ModDef modDef = this.known.get(string);
        return modDef == null || modDef.punish();
    }

    public boolean knows(String string) {
        return this.known.containsKey(string);
    }

    public List<String> allIds() {
        ArrayList<String> arrayList = new ArrayList<String>(this.known.keySet());
        arrayList.sort(String::compareToIgnoreCase);
        return arrayList;
    }

    public List<String> punishmentsFor(String string) {
        ModDef modDef = this.known.get(string);
        return modDef == null ? List.of() : modDef.punishments();
    }

    public List<ModDef> listMods(String string) {
        Category category = string == null || string.isBlank() ? null : ModCatalog.parseCategory(string);
        ArrayList<ModDef> arrayList = new ArrayList<ModDef>();
        for (ModDef modDef3 : this.known.values()) {
            if (!this.isTracked(modDef3.id()) || category != null && modDef3.category() != category) continue;
            arrayList.add(modDef3);
        }
        arrayList.sort((modDef, modDef2) -> modDef.display().compareToIgnoreCase(modDef2.display()));
        return arrayList;
    }

    public Map<Category, Integer> countsByCategory() {
        EnumMap<Category, Integer> enumMap = new EnumMap<Category, Integer>(Category.class);
        for (ModDef modDef : this.known.values()) {
            if (!this.isTracked(modDef.id())) continue;
            enumMap.merge(modDef.category(), 1, Integer::sum);
        }
        return enumMap;
    }

    public String categoryName(String string) {
        ModDef modDef = this.known.get(string);
        return modDef == null ? "UNKNOWN" : modDef.category().name();
    }

    public int knownCount() {
        return this.known.size();
    }

    public int trackedCount() {
        int n = 0;
        for (String string : this.known.keySet()) {
            if (!this.isTracked(string)) continue;
            ++n;
        }
        return n;
    }

    public record ModDef(String id, String display, Category category, List<Signal> signals, boolean punish, List<String> punishments) {
    }

    public static enum Category {
        CHEAT,
        SUSPICIOUS,
        LAUNCHER,
        UTILITY,
        UNKNOWN;

    }

    public record Signal(SignalSource source, List<String> matches, List<String> keys, boolean required) {
        boolean isPassive() {
            return this.source == SignalSource.BRAND || this.source == SignalSource.CHANNEL;
        }

        boolean isActive() {
            return this.source == SignalSource.TRANSLATE || this.source == SignalSource.KEYBIND || this.source == SignalSource.METEOR_VARIANT;
        }
    }

    public static enum SignalSource {
        BRAND,
        CHANNEL,
        TRANSLATE,
        KEYBIND,
        METEOR_VARIANT,
        UNKNOWN;

    }
}


