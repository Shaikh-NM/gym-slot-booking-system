import java.util.*;

public class GetInShape {

    // ---------------- domain ----------------

    private static final class Slot {
        final String centerName;
        final String workoutType;
        final int startTime;
        final int endTime;
        final int totalSeats;
        int seatsAvailable;
        final Set<String> bookedUsers = new HashSet<>();
        // insertion-ordered so notifications go out in the order users showed interest
        final LinkedHashSet<String> interestedUsers = new LinkedHashSet<>();

        Slot(String centerName, String workoutType, int startTime, int endTime, int totalSeats) {
            this.centerName = centerName;
            this.workoutType = workoutType;
            this.startTime = startTime;
            this.endTime = endTime;
            this.totalSeats = totalSeats;
            this.seatsAvailable = totalSeats;
        }

        String format() {
            return centerName + "|" + workoutType + "|" + startTime + "|" + endTime + "|" + seatsAvailable;
        }
    }

    private static final class Center {
        final String name;
        final List<int[]> timings = new ArrayList<>();
        final Set<String> workoutTypes = new HashSet<>();
        // Slots of a center never overlap, so start time uniquely identifies a slot.
        // For multi-day support this becomes Map<day, TreeMap<Integer, Slot>>.
        final TreeMap<Integer, Slot> slotsByStart = new TreeMap<>();

        Center(String name) {
            this.name = name;
        }

        boolean withinTimings(int start, int end) {
            for (int[] t : timings) {
                if (start >= t[0] && end <= t[1]) return true;
            }
            return false;
        }

        boolean overlapsExisting(int start, int end) {
            Map.Entry<Integer, Slot> before = slotsByStart.floorEntry(start); // starts at or before 'start'
            if (before != null && before.getValue().endTime > start) return true;
            Map.Entry<Integer, Slot> after = slotsByStart.ceilingEntry(start); // starts at or after 'start'
            return after != null && after.getValue().startTime < end;
        }
    }

    // ---------------- state ----------------

    private final Map<String, Center> centers = new HashMap<>();

    // ---------------- admin ----------------

    public synchronized void onboardCenter(String centerName, List<String> centerTimings,
                                           List<String> workoutTypes) {
        if (centerName == null || centerName.isEmpty() || centers.containsKey(centerName)
                || centerTimings == null || workoutTypes == null) {
            return;
        }
        Center center = new Center(centerName);
        for (String range : centerTimings) {
            String[] parts = range.split("-");
            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());
            if (start < end) center.timings.add(new int[]{start, end});
        }
        center.workoutTypes.addAll(workoutTypes); // new workout kinds are just new strings
        centers.put(centerName, center);
    }

    public synchronized boolean addWorkoutSlot(String centerName, String workoutType,
                                               int startTime, int endTime, int totalSeats) {
        Center center = centers.get(centerName);
        if (center == null || workoutType == null || !center.workoutTypes.contains(workoutType)) return false;
        if (startTime >= endTime || totalSeats <= 0) return false;
        if (!center.withinTimings(startTime, endTime)) return false;
        if (center.overlapsExisting(startTime, endTime)) return false;

        center.slotsByStart.put(startTime, new Slot(centerName, workoutType, startTime, endTime, totalSeats));
        return true;
    }

    // ---------------- views ----------------

    public synchronized List<String> viewWorkoutAvailabilityByStartTime(String workoutType, String centerName) {
        List<Slot> slots = new ArrayList<>();
        if (centerName == null || centerName.isEmpty() || centerName.equals("*")) {
            for (Center c : centers.values()) collect(c, workoutType, slots);
        } else {
            Center c = centers.get(centerName);
            if (c != null) collect(c, workoutType, slots);
        }
        // start time ASC, then the formatted string lexicographically ASC
        return render(slots, Comparator.<Slot>comparingInt(s -> s.startTime)
                .thenComparing(Slot::format));
    }

    public synchronized List<String> viewWorkoutAvailabilityBySeatsAvailable(String workoutType, String centerName) {
        if (centerName == null || centerName.isEmpty() || centerName.equals("*")) {
            return new ArrayList<>(); // center is mandatory for this view
        }
        List<Slot> slots = new ArrayList<>();
        Center c = centers.get(centerName);
        if (c != null) collect(c, workoutType, slots);
        // seats ASC, then the formatted string lexicographically ASC
        return render(slots, Comparator.<Slot>comparingInt(s -> s.seatsAvailable)
                .thenComparing(Slot::format));
    }

    private void collect(Center center, String workoutType, List<Slot> out) {
        for (Slot s : center.slotsByStart.values()) {
            if (s.workoutType.equals(workoutType)) out.add(s);
        }
    }

    private List<String> render(List<Slot> slots, Comparator<Slot> order) {
        slots.sort(order);
        List<String> result = new ArrayList<>(slots.size());
        for (Slot s : slots) result.add(s.format());
        return result;
    }

    // ---------------- booking ----------------

    public synchronized String bookSession(String userId, String centerName, String workoutType,
                                           int startTime, int endTime) {
        Slot slot = findSlot(centerName, workoutType, startTime, endTime);
        if (slot == null) return "SLOT_NOT_FOUND";
        if (slot.bookedUsers.contains(userId)) return "ALREADY_BOOKED";
        if (slot.seatsAvailable == 0) return "NO_SEATS";

        slot.seatsAvailable--;
        slot.bookedUsers.add(userId);
        slot.interestedUsers.remove(userId); // no longer needs a notification
        return "BOOKED";
    }

    public synchronized String cancelSession(String userId, String centerName, String workoutType,
                                             int startTime, int endTime) {
        Slot slot = findSlot(centerName, workoutType, startTime, endTime);
        if (slot == null) return "SLOT_NOT_FOUND";
        if (!slot.bookedUsers.remove(userId)) return "BOOKING_NOT_FOUND";

        slot.seatsAvailable++;
        return "CANCELLED";
    }

    // ---------------- notify-me ----------------

    public synchronized String addToInterestList(String userId, String centerName, String workoutType,
                                                 int startTime, int endTime) {
        Slot slot = findSlot(centerName, workoutType, startTime, endTime);
        if (slot == null) return "SLOT_NOT_FOUND";
        if (slot.seatsAvailable > 0) return "SEATS_AVAILABLE";
        return slot.interestedUsers.add(userId) ? "INTEREST_ADDED" : "ALREADY_INTERESTED";
    }

    /**
     * Dispatches notifications for a slot that has a free seat. Each interested user is
     * notified once and removed from the list; the returned lines model the push logs.
     */
    public synchronized List<String> notifyInterestedUsers(String centerName, String workoutType,
                                                           int startTime, int endTime) {
        List<String> logs = new ArrayList<>();
        Slot slot = findSlot(centerName, workoutType, startTime, endTime);
        if (slot == null || slot.seatsAvailable == 0) return logs;

        for (String user : slot.interestedUsers) {
            logs.add("NOTIFY|" + user + "|" + centerName + "|" + workoutType + "|" + startTime + "-" + endTime);
        }
        slot.interestedUsers.clear();
        return logs;
    }

    // ---------------- helpers ----------------

    private Slot findSlot(String centerName, String workoutType, int startTime, int endTime) {
        Center center = centers.get(centerName);
        if (center == null) return null;
        Slot slot = center.slotsByStart.get(startTime);
        if (slot == null || slot.endTime != endTime || !slot.workoutType.equals(workoutType)) return null;
        return slot;
    }

    public static void main(String[] args) {
        GetInShape g = new GetInShape();
        g.onboardCenter("connaught_place", List.of("6-9", "18-21"), List.of("weights", "cardio", "yoga", "swimming"));
        g.onboardCenter("bandra_west", List.of("7-10", "19-22"), List.of("weights", "cardio", "yoga"));

        System.out.println(g.addWorkoutSlot("connaught_place", "weights", 6, 7, 100)); // true
        System.out.println(g.addWorkoutSlot("connaught_place", "cardio", 7, 8, 150));  // true
        System.out.println(g.addWorkoutSlot("connaught_place", "yoga", 8, 9, 200));    // true
        System.out.println(g.addWorkoutSlot("bandra_west", "weights", 18, 19, 100));   // false (outside timings)
        System.out.println(g.addWorkoutSlot("bandra_west", "swimming", 19, 20, 100));  // false (type not allowed)
        System.out.println(g.addWorkoutSlot("bandra_west", "cardio", 19, 20, 20));     // true
        System.out.println(g.addWorkoutSlot("bandra_west", "weights", 20, 21, 100));   // true
        System.out.println(g.addWorkoutSlot("bandra_west", "weights", 21, 22, 100));   // true

        System.out.println(g.viewWorkoutAvailabilityByStartTime("weights", "*"));
        System.out.println(g.bookSession("vaibhav", "connaught_place", "weights", 6, 7)); // BOOKED
        System.out.println(g.viewWorkoutAvailabilityByStartTime("weights", "*"));
        System.out.println(g.bookSession("vaibhav", "connaught_place", "weights", 6, 7)); // ALREADY_BOOKED
        System.out.println(g.viewWorkoutAvailabilityBySeatsAvailable("weights", "bandra_west"));

        System.out.println(g.addWorkoutSlot("connaught_place", "yoga", 18, 19, 1));       // true
        System.out.println(g.bookSession("arjun", "connaught_place", "yoga", 18, 19));    // BOOKED
        System.out.println(g.bookSession("rohit", "connaught_place", "yoga", 18, 19));    // NO_SEATS
        System.out.println(g.addToInterestList("rohit", "connaught_place", "yoga", 18, 19)); // INTEREST_ADDED
        System.out.println(g.cancelSession("arjun", "connaught_place", "yoga", 18, 19));  // CANCELLED
        System.out.println(g.notifyInterestedUsers("connaught_place", "yoga", 18, 19));   // [NOTIFY|rohit|...|18-19]
        System.out.println(g.viewWorkoutAvailabilityByStartTime("yoga", "connaught_place"));
    }
}