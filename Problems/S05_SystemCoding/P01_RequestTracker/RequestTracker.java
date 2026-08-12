package Problems.S05_SystemCoding.P01_RequestTracker;


import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class RequestTracker {
    // client makes requestTracker for itself,
    // it just defines the list of Attributes it has exposed to its clients
    // and the length of window for which it wants to get the count....

    private static final int DEFAULT_BUCKET_COUNT = 60;
    private final int bucketCount;
    private final long bucketMillis;
    private final List<Attribute> groupBy;

    private final Map<Attribute, ValueIndex> indexes = new HashMap<>();

    public RequestTracker(long windowMillis, List<Attribute> groupBy) {
        this(windowMillis, DEFAULT_BUCKET_COUNT, groupBy);
    }

    public RequestTracker(long windowMillis, int bucketCount, List<Attribute> groupBy) {
        if (windowMillis <= 0) throw new IllegalArgumentException("window size must be > 0");
        if (bucketCount <= 0) throw new IllegalArgumentException("bucket size must be less than 0");
        if (groupBy == null || groupBy.isEmpty())
            throw new IllegalArgumentException("groupBy list cannot be empty/null.");
        this.bucketMillis = (windowMillis + bucketCount - 1) / bucketCount;
        this.bucketCount = bucketCount;
        this.groupBy = List.copyOf(groupBy);

        for (Attribute attribute : this.groupBy) {
            indexes.put(attribute, new ValueIndex());
        }
    }

    public void record(Request request) {
        if (request == null) throw new NullPointerException("request must be null");

        long period = currentPeriod();
        for (Attribute attribute : this.groupBy) {
            String value = attribute.valueOf(request);// value asscoiated with this attribute.
            if (value == null) {
                continue;
            }
            indexes.get(attribute).getOrCreate(value, bucketCount).record(period, bucketCount);
        }
    }

    public long count(Attribute attribute, String value) {
        ValueIndex index = indexes.get(attribute);

        if (index == null) {
            throw new IllegalArgumentException("Attribute not configured for this attribute.");
        }

        if (value == null) {
            return 0;
        }

        Window window = index.get(value);
        if (window == null) {
            return 0;
        }

        return window.count(currentPeriod(), bucketCount);
    }


    private long currentPeriod() {
        // which period of bucket are we currently in
        return Math.floorDiv(System.currentTimeMillis(), bucketMillis);
    }

    private static final class ValueIndex {
        private final Map<String, Window> byValue = new HashMap<>();

        synchronized Window getOrCreate(String value, int bucketCount) {
            Window window = byValue.get(value);
            if (window == null) {
                window = new Window(bucketCount);
                byValue.put(value, window);
            }
            return window;
        }

        synchronized Window get(String value) {
            return byValue.get(value);
        }
    }


    private static final class Window {
        private final long[] counts;
        private final long[] periods;

        Window(int bucketCount) {
            counts = new long[bucketCount];
            periods = new long[bucketCount];
            Arrays.fill(periods, Long.MIN_VALUE);
        }

        synchronized void record(long period, int bucketCount) {
            int slot = (int) Math.floorMod(period, bucketCount);
            if (periods[slot] != period) { // last recorded value is stored in periods ig
                periods[slot] = period;
                counts[slot] = 0;
            }
            counts[slot]++;
        }

        synchronized long count(long currentPeriod, int bucketCount) {
            long oldestValid = currentPeriod - bucketCount + 1;
            long total = 0;
            for (int slot = 0; slot < bucketCount; slot++) {
                if (periods[slot] >= oldestValid && periods[slot] <= currentPeriod) {
                    total += counts[slot];
                }
            }
            return total;
        }
    }


    public static final class Request {
        private final String ip;
        private final String browserAgent;
        private final String endpoint;

        public Request(String ip, String browserAgent, String endpoint) {
            this.ip = ip;
            this.browserAgent = browserAgent;
            this.endpoint = endpoint;
        }

        public String getIp() {
            return ip;
        }

        public String getBrowserAgent() {
            return browserAgent;
        }

        public String getEndpoint() {
            return endpoint;
        }
    }


    public interface Attribute {
        String name();

        String valueOf(Request request);

        static Attribute of(String name, Function<Request, String> extractor) {
            return new Attribute() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public String valueOf(Request request) {
                    return extractor.apply(request);
                }

                @Override
                public String toString() {
                    return name;
                }
            };
        }

        Attribute IP = of("IP", Request::getIp);
        Attribute BROWSER_AGENT = of("BrowserAgent", Request::getBrowserAgent);
        Attribute ENDPOINT = of("Endpoint", Request::getEndpoint);
    }

}
