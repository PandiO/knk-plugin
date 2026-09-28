package net.knightsandkings.knk.paper.roads;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.roads.RoadBreadcrumbPoint;
import net.knightsandkings.knk.core.domain.roads.RoadProfile;
import net.knightsandkings.knk.core.roads.survey.ProfileLearner;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile;
import net.knightsandkings.knk.core.roads.survey.SurveySample;
import net.knightsandkings.knk.core.roads.survey.SurveyStats;

/**
 * One admin's survey walk (DESIGN §5.3): the samples taken so far, the breadcrumb, and the live
 * estimate. Bukkit-free - {@code RoadSurveyService} feeds it the admin's state each sampling tick through
 * {@link #tick} and reads the world through {@link CrossSectionSampler.Blocks}; the sampling gates
 * ({@link SurveySamplingGate}) and the cross-section are therefore testable with fakes.
 */
public final class RoadSurveySession {
    /** Ticks between two live action-bar estimates. */
    public static final int LIVE_ESTIMATE_PERIOD_TICKS = 40;
    /** Samples needed before the learner's live estimate is shown. */
    public static final int MIN_SAMPLES_FOR_ESTIMATE = 5;

    /** What the session shows in the action bar after a tick. */
    public record Live(int samples, SurveySamplingGate.Verdict verdict, String topMaterials, String width) {
    }

    private final UUID player;
    private final String world;
    private final RoadProfile profile;      // null = learn a new profile
    private final String newProfileName;    // may be null until save
    private final OffsetDateTime startedAt;
    private final CrossSectionSampler sampler;
    private final List<SurveySample> samples = new ArrayList<>();
    private final List<RoadBreadcrumbPoint> breadcrumb = new ArrayList<>();
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private int lastFloorX = Integer.MIN_VALUE;
    private int lastFloorZ = Integer.MIN_VALUE;
    private long lastEstimateTick = Long.MIN_VALUE;
    private ProposedProfile liveEstimate;

    public RoadSurveySession(UUID player, String world, RoadProfile profile, String newProfileName, CrossSectionSampler sampler) {
        this(player, world, profile, newProfileName, sampler, OffsetDateTime.now(ZoneOffset.UTC));
    }

    RoadSurveySession(UUID player, String world, RoadProfile profile, String newProfileName, CrossSectionSampler sampler,
                      OffsetDateTime startedAt) {
        this.player = Objects.requireNonNull(player, "player");
        this.world = Objects.requireNonNull(world, "world");
        this.profile = profile;
        this.newProfileName = newProfileName;
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.startedAt = startedAt;
    }

    public UUID player() {
        return player;
    }

    public String world() {
        return world;
    }

    public Optional<RoadProfile> profile() {
        return Optional.ofNullable(profile);
    }

    public Optional<String> newProfileName() {
        return Optional.ofNullable(newProfileName);
    }

    public OffsetDateTime startedAt() {
        return startedAt;
    }

    public List<SurveySample> samples() {
        return Collections.unmodifiableList(samples);
    }

    public List<RoadBreadcrumbPoint> breadcrumb() {
        return Collections.unmodifiableList(breadcrumb);
    }

    public int sampleCount() {
        return samples.size();
    }

    /**
     * One sampling tick. {@code x, y, z} is the admin's position (feet), {@code yaw} their facing;
     * {@code state} the gate inputs except the movement, which the session derives from the last tick.
     */
    public Live tick(long tick, double x, double y, double z, float yaw, boolean onGround, boolean flying, boolean gliding,
                     boolean insideVehicle, boolean swimming, boolean inWater, CrossSectionSampler.Blocks blocks) {
        double dx = Double.isNaN(lastX) ? 0 : x - lastX;
        double dz = Double.isNaN(lastZ) ? 0 : z - lastZ;
        boolean first = Double.isNaN(lastX);
        lastX = x;
        lastZ = z;
        SurveySamplingGate.PlayerState state = new SurveySamplingGate.PlayerState(onGround, flying, gliding, insideVehicle,
            swimming, inWater, dx, dz);
        SurveySamplingGate.Verdict verdict = first ? SurveySamplingGate.Verdict.STANDING_STILL : SurveySamplingGate.verdict(state);
        if (verdict == SurveySamplingGate.Verdict.SAMPLE) {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int feetY = (int) Math.floor(y - 0.001);
            Optional<CrossSectionSampler.Floor> floor = sampler.floorUnder(blocks, bx, feetY, bz);
            if (floor.isPresent() && (bx != lastFloorX || bz != lastFloorZ)) {
                double[] direction = SurveySamplingGate.direction(dx, dz, yaw);
                SurveySample sample = sampler.sample(blocks, bx, floor.get().y(), bz, floor.get(), SurveySamplingGate.lateral(direction));
                samples.add(sample);
                breadcrumb.add(new RoadBreadcrumbPoint(bx, floor.get().y(), bz, true));
                lastFloorX = bx;
                lastFloorZ = bz;
            }
        }
        if (samples.size() >= MIN_SAMPLES_FOR_ESTIMATE && tick - lastEstimateTick >= LIVE_ESTIMATE_PERIOD_TICKS) {
            lastEstimateTick = tick;
            try {
                liveEstimate = new ProfileLearner().learn(SurveyStats.of(samples));
            } catch (RuntimeException e) {
                liveEstimate = null;
            }
        }
        return new Live(samples.size(), verdict, topMaterials(liveEstimate), width(liveEstimate));
    }

    /** The statistics of this walk alone (what {@code POST api/road-surveys} stores). */
    public SurveyStats stats() {
        return SurveyStats.of(samples);
    }

    static String topMaterials(ProposedProfile estimate) {
        if (estimate == null) {
            return "…";
        }
        List<ProposedProfile.Material> surfaces = new ArrayList<>(estimate.materials());
        surfaces.sort((a, b) -> Double.compare(b.centreShare(), a.centreShare()));
        StringBuilder text = new StringBuilder();
        int n = 0;
        for (ProposedProfile.Material m : surfaces) {
            if (n++ >= 3) {
                break;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(m.material().toLowerCase().replace('_', ' ')).append(' ').append(RoadMessages.percent(m.centreShare()));
        }
        return text.length() == 0 ? "…" : text.toString();
    }

    static String width(ProposedProfile estimate) {
        if (estimate == null) {
            return "…";
        }
        return estimate.widthMin() == estimate.widthMax() ? estimate.widthMin() + " wide" : estimate.widthMin() + "-" + estimate.widthMax() + " wide";
    }
}
