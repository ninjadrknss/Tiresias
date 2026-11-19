package tiresias.spec;

import static tiresias.spec.Spec.*;

import java.util.*;
import java.util.List;

/**
 * Topics: field topic names across the whole spec, for autocomplete while typing a topic, finding the fields under a
 * topic level, and renaming a level everywhere. A level path such as "VCU/Status" covers the topic "VCU/Status"
 * itself and everything under it ("VCU/Status/Temp"), never "VCU/StatusX".
 */
public final class Topics {

    private Topics() {}

    /** Every field with a topic name, in file order. */
    public static List<FieldRef> fields(List<SpecFile> files) {
        List<FieldRef> out = new ArrayList<>();
        for (SpecFile f : files) {
            if (f.loadError != null) continue;
            for (Object o : f.messages) {
                Map<String, Object> m = asMap(o);
                if (m == null) continue;
                List<Object> fds = listOrEmpty(m.get("fields"));
                for (int j = 0; j < fds.size(); j++) if (
                    str(asMap(fds.get(j)), "name") instanceof String nm && !nm.isBlank()
                ) out.add(new FieldRef(f, m, j));
            }
        }
        return out;
    }

    public static String topic(FieldRef r) {
        List<Object> fs = listOrEmpty(r.msg().get("fields"));
        return r.field() < 0 || r.field() >= fs.size() ? "" : strOr(asMap(fs.get(r.field())), "name", "");
    }

    /** A level path without leading or trailing slashes. */
    public static String level(String path) {
        return path == null ? "" : path.strip().replaceAll("^/+|/+$", "");
    }

    /** True if the topic is the level itself or sits under it. */
    public static boolean under(String topic, String level) {
        String lv = level(level);
        return !lv.isEmpty() && (topic.equals(lv) || topic.startsWith(lv + "/"));
    }

    /** The fields whose topics are at or under the level. */
    public static List<FieldRef> usages(List<SpecFile> files, String level) {
        List<FieldRef> out = new ArrayList<>();
        for (FieldRef r : fields(files)) if (under(topic(r), level)) out.add(r);
        return out;
    }

    /** The topic after renaming the level from → to (the topic must be under from). */
    public static String renamed(String topic, String from, String to) {
        if (!under(topic, from)) return topic;
        return level(to) + topic.substring(level(from).length());
    }

    /** Renames the level in every topic under it. Returns how many topics changed in each file. */
    public static Map<SpecFile, Integer> rename(List<SpecFile> files, String from, String to) {
        Map<SpecFile, Integer> changed = new LinkedHashMap<>();
        if (level(from).isEmpty() || level(to).isEmpty() || level(from).equals(level(to))) return changed;
        for (FieldRef r : usages(files, from)) {
            Map<String, Object> fd = asMap(listOrEmpty(r.msg().get("fields")).get(r.field()));
            fd.put("name", renamed(topic(r), from, to));
            changed.merge(r.file(), 1, Integer::sum);
        }
        return changed;
    }

    /** Topics the rename would produce that another field (one not being renamed) already publishes. */
    public static Set<String> collisions(List<SpecFile> files, String from, String to) {
        Set<String> others = new HashSet<>(),
            out = new LinkedHashSet<>();
        List<FieldRef> moving = usages(files, from);
        for (FieldRef r : fields(files)) if (!under(topic(r), from)) others.add(topic(r));
        for (FieldRef r : moving) {
            String now = renamed(topic(r), from, to);
            if (others.contains(now)) out.add(now);
        }
        return out;
    }

    /**
     * A suggestion while typing a topic: the full text to put in the field, how many topics use it, and whether
     * it's a complete topic (false means a level with more under it; its text then ends with '/').
     */
    public record Completion(String text, int uses, boolean complete) {}

    /**
     * Suggestions for the level being typed, from the spec's existing topics. For "VCU/Sta" they're the levels after
     * "VCU/" that start with "Sta" (then those that contain it), most used first; a level with more under it is
     * offered as "VCU/Status/" so typing can continue.
     */
    public static List<Completion> complete(List<SpecFile> files, String typed, int limit) {
        String t = typed == null ? "" : typed.stripLeading();
        int cut = t.lastIndexOf('/');
        String parent = cut < 0 ? "" : t.substring(0, cut + 1),
            part = t.substring(cut + 1).toLowerCase(Locale.ROOT);
        Map<String, int[]> next = new LinkedHashMap<>(); // level -> {uses, has children, is a topic itself}
        for (FieldRef r : fields(files)) {
            String tp = topic(r);
            if (!tp.startsWith(parent) || tp.length() == parent.length()) continue;
            String rest = tp.substring(parent.length());
            int slash = rest.indexOf('/');
            String lv = slash < 0 ? rest : rest.substring(0, slash);
            int[] c = next.computeIfAbsent(lv, k -> new int[3]);
            c[0]++;
            if (slash >= 0) c[1] = 1;
            else c[2] = 1;
        }
        List<Completion> starts = new ArrayList<>(),
            contains = new ArrayList<>();
        for (Map.Entry<String, int[]> e : next.entrySet()) {
            String lv = e.getKey(),
                low = lv.toLowerCase(Locale.ROOT);
            int[] c = e.getValue();
            if (!part.isEmpty() && !low.contains(part)) continue;
            List<Completion> into = low.startsWith(part) ? starts : contains;
            if (c[1] == 1) into.add(new Completion(parent + lv + "/", c[0], false));
            if (c[2] == 1) into.add(new Completion(parent + lv, 1, true));
        }
        Comparator<Completion> byUse = Comparator.comparingInt((Completion c) -> -c.uses()).thenComparing(
            Completion::text
        );
        starts.sort(byUse);
        contains.sort(byUse);
        List<Completion> out = new ArrayList<>(starts);
        out.addAll(contains);
        // typing the whole of a complete topic offers nothing new
        out.removeIf(c -> c.complete() && c.text().equals(t));
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
