// Fake candump: streams realistic CAN traffic built from the spec, for testing Tiresias's live view.
//
// It prints `candump -L` lines (`(1700000000.123456) can0 123#F580`) to stdout as frames come due, so Tiresias's
// Listen live can run it as its command: choose "On this computer" and set Command to
//   java /path/to/Tiresias/tools/FakeCandump.java --iface {iface}
// Nothing is sent to a real bus. It's a single file with no dependencies (JDK 21+).
//
// What it sends:
//   - Every message in the spec at its sim_freq period (milliseconds; 1000 ms when missing), with a little jitter.
//   - Point values from each point's "sim": a random walk within min..max for sweeps, a weighted pick that changes
//     now and then for options, and the point's default (or 0) when there's no sim.
//   - The shutdown pins message (0x123) stepping through a scripted story: closed, then open at the cockpit BRB,
//     TSMS, HVD, the BSPD latch, before the BMS latch (every pin 0), and closed again. Like the real loop, a break
//     reads 0 on its own pin and every pin after it, so a pin never reads 1 past an open stage.
//   - The VCU eFuses healthy, except that now and then (every 20-50 s) a random one faults for 5-12 s. The pumps
//     and fans are in auto (control state 1) and switch themselves on and off; the rest are on (control state 0).
//   - The VCU's faults (0x502) and the BMS's faults (0x89) clear, except that every 15-40 s one of each comes on for
//     4-15 s (critical ones less often).
//   - One node that goes quiet for a while every so often (the IMD by default), so its IDs go stale.
//
// Values are packed the way Tiresias and Calypso decode them: big-endian MSB-first bits, two's complement for
// signed points, byte-swapped little-endian points, IEEE 754 floats, and the formatter's divide/multiply.
//
// Examples:
//   java tools/FakeCandump.java                       everything, forever
//   java tools/FakeCandump.java --only bms,vcu        just those files (or IDs like 0x81)
//   java tools/FakeCandump.java --speed 0.25          a quarter of the normal frame rates
//   java tools/FakeCandump.java --list                what it would send, and the total frame rate
//   java tools/FakeCandump.java --duration 10 --fast  10 simulated seconds, as fast as possible

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class FakeCandump {

    static final long SHUTDOWN_ID = 0x123;
    /** Fault flag messages (one bit per fault, Critical or Non-Critical in each field's topic): VCU, then BMS. */
    static final List<Long> FAULT_IDS = List.of(0x502L, 0x89L);
    static final List<Long> EFUSE_IDS = LongStream.rangeClosed(0xEF0, 0xEFA).boxed().toList();
    /** eFuses in auto (control state 1: the VCU switches them itself): radiator fans, battery box fans, pumps. */
    static final List<Long> AUTO_EFUSES = List.of(0xEF4L, 0xEF5L, 0xEF6L, 0xEF7L);
    /** The sensed pins in loop order, as Tiresias's ShutdownLoop.STAGES has them. */
    static final List<String> LOOP_PINS = List.of(
        "bms_gpio", "imd_gpio", "bspd_gpio", "hv_c", "bots_gpio", "inertia_sw_gpio", "ckpt_gpio", "tsms_gpio", "hvd_gpio");
    /**
     * The shutdown pins story: seconds to hold, and the first pin that reads 0 (null: the loop is closed). That pin and
     * every pin after it in the loop read 0, the ones before it 1: current can't get past an open stage, so no later
     * pin can read 1.
     */
    static final Object[][] SHUTDOWN_STORY = {
        { 8, null }, // closed
        { 6, "ckpt_gpio" }, // open at the cockpit BRB
        { 6, "tsms_gpio" }, // open at TSMS
        { 6, "hvd_gpio" }, // open at HVD
        { 6, "bspd_gpio" }, // open at the BSPD latch (or the IMD latch to it)
        { 6, "bms_gpio" }, // every pin 0: somewhere from the VCU through the BMS latch
    };

    /** Auto eFuses run AUTO_ON seconds of every AUTO_CYCLE. */
    static final double AUTO_CYCLE = 45, AUTO_ON = 30;

    static Random rnd = new Random();

    static double uniform(double lo, double hi) {
        return lo + rnd.nextDouble() * (hi - lo);
    }

    // ---- a minimal JSON reader: objects as LinkedHashMap, arrays as ArrayList, numbers as Double

    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text);
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) throw j.err("trailing text");
            return v;
        }

        RuntimeException err(String what) {
            return new IllegalArgumentException(what + " at character " + i);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    if (s.charAt(i++) != ':') throw err("expected :");
                    m.put(k, value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw err("expected , or }");
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw err("expected , or ]");
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected character '" + c + "'");
            return Double.parseDouble(s.substring(st, i));
        }

        String string() {
            if (s.charAt(i++) != '"') throw err("expected a string");
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> b.append(e);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    static double num(Object o, double dflt) {
        return o instanceof Number n ? n.doubleValue() : dflt;
    }

    static Long parseId(Object o) {
        if (!(o instanceof String s) || !s.strip().toLowerCase(Locale.ROOT).startsWith("0x")) return null;
        try {
            return Long.parseLong(s.strip().substring(2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** What the decoder divides the raw value by: the formatter's divide arg, 1/arg for multiply, else 1. */
    static double divisor(Map<String, Object> p) {
        Map<String, Object> f = map(p.get("formatter"));
        if (f != null) {
            long arg = (long) num(f.get("arg"), 0); // firmware-rs cangen casts the arg to u32
            if (arg <= 0) return 1;
            if ("divide".equals(f.get("key"))) return arg;
            if ("multiply".equals(f.get("key"))) return 1.0 / arg;
            return 1;
        }
        if (p.get("format") instanceof String fmt && fmt.matches("divide\\d+")) return Long.parseLong(fmt.substring(6));
        return 1;
    }

    // ---- points and messages

    static final class Point {
        final Map<String, Object> p;
        final String name;
        final int size, offset;
        final boolean parsed, signed, little, f32;
        final double div;
        final Map<String, Object> sim;
        double value;
        int direction = rnd.nextBoolean() ? 1 : -1;
        /** Set by a story rather than the sim: eFuse flags and control state, and the fault bits. */
        boolean scripted;
        /** The MQTT topic of the field that carries this point ("VCU/Faults/Critical/..."), if any. */
        String topic = "";

        Point(Map<String, Object> p, int offset) {
            this.p = p;
            this.name = p.get("name") instanceof String s ? s : "";
            this.size = (int) num(p.get("size"), 0);
            this.offset = offset;
            this.parsed = !Boolean.FALSE.equals(p.get("parse"));
            this.signed = Boolean.TRUE.equals(p.get("signed"));
            this.little = "little".equals(p.get("endianness")) && size % 8 == 0;
            this.f32 = Boolean.TRUE.equals(p.get("ieee754_f32")) && size == 32;
            this.div = divisor(p);
            this.sim = map(p.get("sim"));
            this.value = start();
            // eFuses are normally on and healthy; the spec's sim would flip them at random
            if (name.equals("is_faulted") || name.equals("is_enabled") || name.equals("control_state")) {
                scripted = true;
                value = name.equals("is_enabled") ? 1 : 0; // control_state 0: on
            }
        }

        double start() {
            if (sim != null && sim.containsKey("options")) return pick();
            if (sim != null && sim.get("min") instanceof Number lo && sim.get("max") instanceof Number hi) return round(
                uniform(lo.doubleValue(), hi.doubleValue())
            );
            return num(p.get("default"), 0);
        }

        double pick() {
            List<List<?>> opts = new ArrayList<>();
            for (Object o : list(sim.get("options"))) if (o instanceof List<?> l && l.size() == 2) opts.add(l);
            if (opts.isEmpty()) return 0;
            double total = 0;
            for (List<?> o : opts) total += num(o.get(1), 0);
            double r = rnd.nextDouble() * total, acc = 0;
            for (List<?> o : opts) {
                acc += num(o.get(1), 0);
                if (r <= acc) return num(o.get(0), 0);
            }
            return num(opts.getLast().get(0), 0);
        }

        double round(double v) {
            return sim != null && Boolean.TRUE.equals(sim.get("round")) ? Math.round(v) : v;
        }

        void step() {
            if (scripted || sim == null) return;
            if (sim.containsKey("options")) {
                if (rnd.nextDouble() < 0.03) value = pick(); // options change now and then, not every frame
                return;
            }
            if (!(sim.get("min") instanceof Number lo) || !(sim.get("max") instanceof Number hi)) return;
            double inc = uniform(num(sim.get("inc_min"), 0), num(sim.get("inc_max"), 0));
            if (rnd.nextDouble() < 0.08) direction = -direction;
            double v = value + direction * inc;
            if (v > hi.doubleValue() || v < lo.doubleValue()) {
                direction = -direction;
                v = Math.min(hi.doubleValue(), Math.max(lo.doubleValue(), v));
            }
            value = round(v);
        }

        long raw() {
            if (f32) return Float.floatToIntBits((float) value) & 0xFFFFFFFFL;
            long r = Math.round(value * div);
            if (signed) r = Math.max(-(1L << (size - 1)), Math.min((1L << (size - 1)) - 1, r));
            else r = Math.max(0, Math.min(size >= 63 ? Long.MAX_VALUE : (1L << size) - 1, r));
            if (size < 64) r &= (1L << size) - 1;
            if (little) {
                long s = 0;
                for (int k = 0; k < size / 8; k++) s = (s << 8) | ((r >>> (8 * k)) & 0xFF);
                r = s;
            }
            return r;
        }
    }

    static final class Message {
        final long id;
        final boolean ext;
        final String desc, source;
        final double period;
        final List<Point> points = new ArrayList<>();
        final int length;
        double next;

        Message(Map<String, Object> m, String source) {
            this.id = parseId(m.get("id"));
            this.ext = Boolean.TRUE.equals(m.get("is_ext")) || id > 0x7FF;
            this.desc = m.get("desc") instanceof String s ? s : "";
            this.source = source;
            double ms = num(m.get("sim_freq"), 0);
            this.period = ms > 0 ? ms / 1000 : 1.0;
            int off = 0;
            List<Point> all = new ArrayList<>(); // every point in spec order, for the fields' 1-based indexes
            for (Object o : list(m.get("points"))) {
                Map<String, Object> p = map(o);
                if (p == null) {
                    all.add(null);
                    continue;
                }
                Point pt = new Point(p, off);
                off += pt.size;
                all.add(pt);
                if (pt.size >= 1 && pt.size <= 64 && pt.parsed) points.add(pt);
            }
            this.length = (off + 7) / 8;
            for (Object o : list(m.get("fields"))) {
                Map<String, Object> f = map(o);
                if (f == null || !(f.get("name") instanceof String topic)) continue;
                for (Object v : list(f.get("values"))) {
                    int k = (int) num(v, 0);
                    if (k >= 1 && k <= all.size() && all.get(k - 1) != null) all.get(k - 1).topic = topic;
                }
            }
        }

        String data(Map<String, Integer> pins) {
            byte[] bytes = new byte[length];
            for (Point pt : points) {
                long raw;
                if (pins != null && pins.containsKey(pt.name)) raw = pins.get(pt.name);
                else {
                    pt.step();
                    raw = pt.raw();
                }
                for (int b = 0; b < pt.size; b++) {
                    int bit = pt.offset + b;
                    if (bit / 8 >= length) break;
                    if (((raw >>> (pt.size - 1 - b)) & 1) != 0) bytes[bit / 8] |= (byte) (0x80 >>> (bit % 8));
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte x : bytes) sb.append(String.format("%02X", x & 0xFF));
            return sb.toString();
        }

        String line(double ts, String iface, Map<String, Integer> pins) {
            String ident = ext ? String.format("%08X", id) : String.format("%03X", id);
            return String.format(Locale.ROOT, "(%.6f) %s %s#%s", ts, iface, ident, data(pins));
        }
    }

    /** Random fault episodes: every `every` seconds (a random gap in that range) one item is active for `length`. */
    static final class Episodes<T> {
        final List<T> items;
        final double[] weights;
        final double everyLo, everyHi, lenLo, lenHi;
        final Deque<Object[]> timeline = new ArrayDeque<>(); // {start, end, item}
        double nextStart;

        Episodes(List<T> items, double[] weights, double everyLo, double everyHi, double lenLo, double lenHi) {
            this.items = items;
            this.weights = weights;
            this.everyLo = everyLo;
            this.everyHi = everyHi;
            this.lenLo = lenLo;
            this.lenHi = lenHi;
            this.nextStart = uniform(everyLo, everyHi) * 0.5;
        }

        T choose() {
            if (weights == null) return items.get(rnd.nextInt(items.size()));
            double total = Arrays.stream(weights).sum(), r = rnd.nextDouble() * total, acc = 0;
            for (int k = 0; k < items.size(); k++) if (r <= (acc += weights[k])) return items.get(k);
            return items.getLast();
        }

        @SuppressWarnings("unchecked")
        T activeAt(double t) {
            if (items.isEmpty()) return null;
            while (nextStart <= t) {
                timeline.addLast(new Object[] { nextStart, nextStart + uniform(lenLo, lenHi), choose() });
                if (timeline.size() > 8) timeline.removeFirst();
                nextStart += uniform(everyLo, everyHi);
            }
            for (Iterator<Object[]> it = timeline.descendingIterator(); it.hasNext(); ) {
                Object[] e = it.next();
                if ((double) e[0] <= t && t < (double) e[1]) return (T) e[2];
            }
            return null;
        }
    }

    /** The shutdown pins (name to 0/1) at t seconds into the run. */
    static Map<String, Integer> storyAt(double t) {
        double cycle = 0;
        for (Object[] st : SHUTDOWN_STORY) cycle += (int) st[0];
        t %= cycle;
        String open = null;
        for (Object[] st : SHUTDOWN_STORY) {
            if (t < (int) st[0]) {
                open = (String) st[1];
                break;
            }
            t -= (int) st[0];
        }
        Map<String, Integer> pins = new HashMap<>();
        boolean broken = false;
        for (String p : LOOP_PINS) {
            if (p.equals(open)) broken = true;
            pins.put(p, broken ? 0 : 1);
        }
        return pins;
    }

    static List<Message> load(Path spec, String only, String skip) throws IOException {
        List<String> want = split(only), drop = split(skip);
        List<Message> msgs = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(spec)) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path path : files) {
            String source = path.getFileName().toString().replaceFirst("\\.json$", "");
            Object data;
            try {
                data = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
            } catch (RuntimeException | IOException e) {
                continue;
            }
            for (Object o : list(data)) {
                Map<String, Object> m = map(o);
                if (m == null || parseId(m.get("id")) == null) continue;
                Message msg = new Message(m, source);
                if (!want.isEmpty() && !matches(want, msg)) continue;
                if (!drop.isEmpty() && matches(drop, msg)) continue;
                msgs.add(msg);
            }
        }
        return msgs;
    }

    static List<String> split(String s) {
        if (s == null) return List.of();
        return Arrays.stream(s.split(",")).map(x -> x.strip().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty()).toList();
    }

    static boolean matches(List<String> keys, Message m) {
        for (String k : keys) if (k.equals(m.source.toLowerCase(Locale.ROOT)) || Objects.equals(parseId(k), m.id)) return true;
        return false;
    }

    /** The spec folder: --spec, else Odyssey-Definitions/can-messages next to this file's repo or the working folder. */
    static Path findSpec(String given) {
        if (given != null) return Path.of(given);
        List<Path> starts = new ArrayList<>();
        String self = System.getProperty("jdk.launcher.sourcefile"); // set when run as `java FakeCandump.java`
        if (self != null) starts.add(Path.of(self).toAbsolutePath().getParent());
        starts.add(Path.of("").toAbsolutePath());
        for (Path s : starts) for (Path d = s; d != null; d = d.getParent()) {
            Path c = d.resolve("Odyssey-Definitions").resolve("can-messages");
            if (Files.isDirectory(c)) return c;
        }
        return Path.of("Odyssey-Definitions", "can-messages");
    }

    static final String HELP = """
        Usage: java tools/FakeCandump.java [options]

        Prints a realistic candump -L stream built from the spec, for testing Tiresias's Listen live
        (choose "On this computer" and set the command to: java /path/to/tools/FakeCandump.java --iface {iface}).

          --spec DIR           spec folder of message JSON files (default: Odyssey-Definitions/can-messages)
          --iface NAME         interface name to print (default can0)
          --only LIST          comma-separated files (bms, vcu) or IDs (0x81) to send; the rest are skipped
          --skip LIST          comma-separated files or IDs to leave out
          --speed X            multiply every frame rate by this (default 1)
          --dropout FILE       file whose messages go quiet now and then (default imd; "" for none)
          --dropout-every S    seconds between dropouts (default 40)
          --dropout-for S      how long each dropout lasts (default 10)
          --duration S         stop after this many seconds (default: run forever)
          --fast               don't wait: print simulated time as fast as possible
          --seed N             random seed, for a repeatable stream
          --list               list what would be sent, then exit
        """;

    public static void main(String[] args) throws Exception {
        String spec = null, iface = "can0", only = null, skip = null, dropout = "imd";
        double speed = 1, dropEvery = 40, dropFor = 10, duration = 0;
        boolean fast = false, listOnly = false;
        try {
            for (int k = 0; k < args.length; k++) {
                String a = args[k];
                switch (a) {
                    case "--spec" -> spec = args[++k];
                    case "--iface" -> iface = args[++k];
                    case "--only" -> only = args[++k];
                    case "--skip" -> skip = args[++k];
                    case "--speed" -> speed = Double.parseDouble(args[++k]);
                    case "--dropout" -> dropout = args[++k];
                    case "--dropout-every" -> dropEvery = Double.parseDouble(args[++k]);
                    case "--dropout-for" -> dropFor = Double.parseDouble(args[++k]);
                    case "--duration" -> duration = Double.parseDouble(args[++k]);
                    case "--fast" -> fast = true;
                    case "--seed" -> rnd = new Random(Long.parseLong(args[++k]));
                    case "--list" -> listOnly = true;
                    case "-h", "--help" -> {
                        System.out.print(HELP);
                        return;
                    }
                    default -> throw new IllegalArgumentException("Unknown option " + a);
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            System.err.println(
                (e instanceof ArrayIndexOutOfBoundsException ? "An option is missing its value." : e.getMessage()) + "\n\n" + HELP
            );
            System.exit(2);
        }
        if (speed <= 0) {
            System.err.println("--speed must be above 0");
            System.exit(2);
        }
        Path specDir = findSpec(spec);
        if (!Files.isDirectory(specDir)) {
            System.err.println("No spec folder at " + specDir.toAbsolutePath() + ". Pass it with --spec.");
            System.exit(2);
        }
        List<Message> msgs = load(specDir, only, skip);
        if (msgs.isEmpty()) {
            System.err.println("No messages to send. Check --spec (" + specDir + ") and --only/--skip.");
            System.exit(2);
        }
        if (listOnly) {
            double total = 0;
            List<Message> sorted = new ArrayList<>(msgs);
            sorted.sort(Comparator.comparing((Message m) -> m.ext).thenComparingLong(m -> m.id));
            for (Message m : sorted) {
                double hz = speed / m.period;
                total += hz;
                System.out.printf(
                    Locale.ROOT,
                    "%-10s %-12s %7.2f Hz  %s%n",
                    m.ext ? String.format("0x%08X", m.id) : String.format("0x%03X", m.id),
                    m.source,
                    hz,
                    m.desc
                );
            }
            System.out.printf(Locale.ROOT, "%d messages, about %.0f frames per second%n", msgs.size(), total);
            return;
        }

        Map<Long, Episodes<String>> faultStories = new HashMap<>();
        Map<Long, Double> autoPhase = new HashMap<>();
        for (Message m : msgs) {
            m.next = uniform(0, m.period / speed); // spread the first frames out so they don't all land at once
            if (!m.ext && FAULT_IDS.contains(m.id)) {
                List<String> names = new ArrayList<>();
                List<Double> weights = new ArrayList<>();
                for (Point pt : m.points) {
                    pt.scripted = true;
                    pt.value = 0;
                    names.add(pt.name);
                    String t = pt.topic.toLowerCase(Locale.ROOT);
                    weights.add(t.contains("/critical/") ? 1.0 : 4.0); // critical faults come up less often
                }
                double[] w = weights.stream().mapToDouble(Double::doubleValue).toArray();
                faultStories.put(m.id, new Episodes<>(names, w, 15, 40, 4, 15));
            }
            if (AUTO_EFUSES.contains(m.id)) autoPhase.put(m.id, uniform(0, AUTO_CYCLE));
        }
        Episodes<Long> efuseStory = new Episodes<>(EFUSE_IDS, null, 20, 50, 5, 12);

        PriorityQueue<Message> due = new PriorityQueue<>(Comparator.comparingDouble(m -> m.next));
        due.addAll(msgs);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        double startWall = System.currentTimeMillis() / 1000.0;
        long startNs = System.nanoTime(); // timestamps: the wall clock at start, plus elapsed time to the microsecond
        while (true) {
            Message m = due.poll();
            double at = m.next;
            if (duration > 0 && at > duration) break;
            double ts;
            if (fast) ts = startWall + at;
            else {
                double wait = at - (System.nanoTime() - startNs) / 1e9;
                if (wait > 0) {
                    out.flush();
                    if (out.checkError()) return; // the reader went away (Tiresias stopped listening)
                    Thread.sleep((long) (wait * 1000));
                }
                ts = startWall + (System.nanoTime() - startNs) / 1e9;
            }
            m.next = at + (m.period / speed) * uniform(0.95, 1.05);
            due.add(m);
            if (!dropout.isEmpty() && m.source.equalsIgnoreCase(dropout) && dropEvery > 0 && at % dropEvery > dropEvery - dropFor)
                continue; // this node is quiet right now
            Map<String, Integer> pins = !m.ext && m.id == SHUTDOWN_ID ? storyAt(at) : null;
            Episodes<String> faults = m.ext ? null : faultStories.get(m.id);
            if (faults != null) {
                String active = faults.activeAt(at);
                for (Point pt : m.points) pt.value = pt.name.equals(active) ? 1 : 0;
            }
            if (EFUSE_IDS.contains(m.id)) {
                boolean faulted = Objects.equals(efuseStory.activeAt(at), m.id),
                    auto = autoPhase.containsKey(m.id),
                    // in auto the VCU runs it part of the time (a fan or pump cycling); otherwise it's commanded on
                    on = !faulted && (!auto || (at + autoPhase.get(m.id)) % AUTO_CYCLE < AUTO_ON);
                for (Point pt : m.points) {
                    if (pt.name.equals("is_faulted")) pt.value = faulted ? 1 : 0;
                    else if (pt.name.equals("is_enabled")) pt.value = on ? 1 : 0;
                    else if (pt.name.equals("control_state")) pt.value = auto ? 1 : 0;
                    else if (pt.name.equals("current")) {
                        pt.scripted = !on; // an eFuse that's off (faulted, or switched off in auto) draws 0 A
                        if (!on) pt.value = 0;
                    }
                }
            }
            out.println(m.line(ts, iface, pins));
        }
        out.flush();
    }
}
