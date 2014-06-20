package edu.berkeley.cs.amplab.smash4j;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Multiset;

import edu.berkeley.cs.amplab.fastaparser.FastaReader;
import edu.berkeley.cs.amplab.smash4j.Smash4J.VariantProto;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VariantEvaluator {

  public static class Builder {

    private int maxIndelLength = 50;
    private int maxSvBreakpointDistance = 100;
    private int maxVariantLengthDifference = 100;
    private Optional<FastaReader.Callback.FastaFile> reference = Optional.absent();
    private int rescueWindowSize = 50;

    private Builder() {}

    public VariantEvaluator build() {
      return new VariantEvaluator(
          reference,
          maxIndelLength,
          maxSvBreakpointDistance,
          maxVariantLengthDifference,
          rescueWindowSize);
    }

    public Builder setMaxIndelLength(int maxIndelLength) {
      this.maxIndelLength = maxIndelLength;
      return this;
    }

    public Builder setMaxSvBreakpointDistance(int maxSvBreakpointDistance) {
      this.maxSvBreakpointDistance = maxSvBreakpointDistance;
      return this;
    }

    public Builder setMaxVariantLengthDifference(int maxVariantLengthDifference) {
      this.maxVariantLengthDifference = maxVariantLengthDifference;
      return this;
    }

    public Builder setReference(FastaReader.Callback.FastaFile reference) {
      this.reference = Optional.of(reference);
      return this;
    }

    public Builder setRescueWindowSize(int rescueWindowSize) {
      this.rescueWindowSize = rescueWindowSize;
      return this;
    }
  }

  public static class ContigStats {

    private static Multiset<VariantType> countByType(NavigableMap<?, VariantProto> variants) {
      Multiset<VariantType> counts = HashMultiset.create();
      for (Map.Entry<?, VariantProto> entry : variants.entrySet()) {
        counts.add(VariantType.getType(entry.getValue()));
      }
      return counts;
    }

    static ContigStats create(
        String contig,
        NavigableMap<Long, VariantProto> trueVariants,
        NavigableMap<Long, VariantProto> predictedVariants,
        Iterable<Long> truePositiveLocations,
        Iterable<Long> falsePositiveLocations,
        Iterable<Long> falseNegativeLocations,
        Iterable<Long> incorrectPredictions,
        GenotypeConcordance concordance) {
      NavigableMap<Long, VariantProto>
          truePositives = filter(predictedVariants, truePositiveLocations),
          falsePositives = filter(predictedVariants, falsePositiveLocations),
          falseNegatives = filter(trueVariants, falseNegativeLocations);
      Multimap<VariantType, Long> incorrectPredictionsMultimap = ArrayListMultimap.create();
      for (Long location : incorrectPredictions) {
        incorrectPredictionsMultimap.put(
            VariantType.getType(predictedVariants.get(location)),
            location);
      }
      return new ContigStats(
          contig,
          trueVariants,
          predictedVariants,
          truePositives,
          falsePositives,
          falseNegatives,
          countByType(trueVariants),
          countByType(predictedVariants),
          countByType(truePositives),
          countByType(falsePositives),
          countByType(falseNegatives),
          incorrectPredictionsMultimap,
          concordance);
    }

    private static <X extends Comparable<? super X>, Y> NavigableMap<X, Y> filter(
        Map<? super X, ? extends Y> map,
        Iterable<? extends X> keys) {
      NavigableMap<X, Y> filtered = new TreeMap<>();
      for (X key : keys) {
        filtered.put(key, map.get(key));
      }
      return filtered;
    }

    private Optional<Multiset<VariantType>> allKnownFalsePositiveCounts = Optional.absent();
    private final GenotypeConcordance concordance;
    private final String contig;
    private Optional<Multiset<VariantType>> correctKnownFalsePositiveCounts = Optional.absent();
    private final Multiset<VariantType> falseNegativeCounts;
    private final NavigableMap<Long, VariantProto> falseNegatives;
    private final Multiset<VariantType> falsePositiveCounts;
    private final NavigableMap<Long, VariantProto> falsePositives;
    private final Multimap<VariantType, Long> incorrectPredictions;
    private Optional<Multiset<VariantType>> knownFalsePositiveCounts = Optional.absent();
    private Optional<NavigableMap<Long, VariantProto>> knownFalsePositives = Optional.absent();
    private final Multiset<VariantType> predictedVariantCounts;
    private final NavigableMap<Long, VariantProto> predictedVariants;
    private Optional<Multiset<VariantType>> rescuedVariantCounts = Optional.absent();
    private Optional<NavigableMap<Long, VariantProto>> rescuedVariants = Optional.absent();
    private final Multiset<VariantType> truePositiveCounts;
    private final NavigableMap<Long, VariantProto> truePositives;
    private final Multiset<VariantType> trueVariantCounts;
    private final NavigableMap<Long, VariantProto> trueVariants;

    private ContigStats(
        String contig,
        NavigableMap<Long, VariantProto> trueVariants,
        NavigableMap<Long, VariantProto> predictedVariants,
        NavigableMap<Long, VariantProto> truePositives,
        NavigableMap<Long, VariantProto> falsePositives,
        NavigableMap<Long, VariantProto> falseNegatives,
        Multiset<VariantType> trueVariantCounts,
        Multiset<VariantType> predictedVariantCounts,
        Multiset<VariantType> truePositiveCounts,
        Multiset<VariantType> falsePositiveCounts,
        Multiset<VariantType> falseNegativeCounts,
        Multimap<VariantType, Long> incorrectPredictions,
        GenotypeConcordance concordance) {
      this.contig = contig;
      this.trueVariants = trueVariants;
      this.predictedVariants = predictedVariants;
      this.truePositives = truePositives;
      this.falsePositives = falsePositives;
      this.falseNegatives = falseNegatives;
      this.trueVariantCounts = trueVariantCounts;
      this.predictedVariantCounts = predictedVariantCounts;
      this.truePositiveCounts = truePositiveCounts;
      this.falsePositiveCounts = falsePositiveCounts;
      this.falseNegativeCounts = falseNegativeCounts;
      this.incorrectPredictions = incorrectPredictions;
      this.concordance = concordance;
    }

    public Optional<Multiset<VariantType>> allKnownFalsePositiveCounts() {
      return allKnownFalsePositiveCounts;
    }

    public GenotypeConcordance concordance() {
      return concordance;
    }

    public String contig() {
      return contig;
    }

    public Optional<Multiset<VariantType>> correctKnownFalsePositiveCounts() {
      return correctKnownFalsePositiveCounts;
    }

    public Multiset<VariantType> falseNegativeCounts() {
      return falseNegativeCounts;
    }

    public NavigableMap<Long, VariantProto> falseNegatives() {
      return falseNegatives;
    }

    public Multiset<VariantType> falsePositiveCounts() {
      return falsePositiveCounts;
    }

    public NavigableMap<Long, VariantProto> falsePositives() {
      return falsePositives;
    }

    public Multimap<VariantType, Long> incorrectPredictions() {
      return incorrectPredictions;
    }

    public Optional<Multiset<VariantType>> knownFalsePositiveCounts() {
      return knownFalsePositiveCounts;
    }

    public Optional<NavigableMap<Long, VariantProto>> knownFalsePositives() {
      return knownFalsePositives;
    }

    public Multiset<VariantType> predictedVariantCounts() {
      return predictedVariantCounts;
    }

    public NavigableMap<Long, VariantProto> predictedVariants() {
      return predictedVariants;
    }

    private static class RescueMission {

      static RescueMission create(
          FastaReader.Callback.FastaFile reference,
          int rescueWindowSize,
          VariantProto variant,
          NavigableMap<Long, VariantProto> truePositives,
          NavigableMap<Long, VariantProto> falsePositives,
          NavigableMap<Long, VariantProto> falseNegatives) {
        //  num_new_tp = _type_dict()
        //  num_fp_removed = _type_dict()
        //  rescuer = SequenceRescuer(false_negatives.chrom,loc,false_negatives,false_positives,true_positives,ref,window)
        //  if not rescuer or not rescuer.rescued:
        //      return num_new_tp,num_fp_removed,[]
        //  # now the whole truth window becomes true positives
        //  # and the whole predicted window is removed from false positives
        //  for variant in rescuer.truthWindowQueue[rescuer.windowsRescued[0]]:
        //      false_negatives._remove_variant(variant.pos)
        //      num_new_tp[variant.var_type] += 1
        //  for variant in rescuer.predictWindowQueue[rescuer.windowsRescued[1]]:
        //      false_positives._remove_variant(variant.pos)
        //      num_fp_removed[variant.var_type] += 1
        //  return num_new_tp,num_fp_removed,rescuer.predictWindowQueue[rescuer.windowsRescued[1]]
        throw new UnsupportedOperationException();
      }

      private final Multiset<VariantProto> newTruePositives;
      private final Multiset<VariantProto> removeFalsePositives;
      private final NavigableMap<Long, VariantProto> rescuedVariants;

      private RescueMission(
          Multiset<VariantProto> newTruePositives,
          Multiset<VariantProto> removeFalsePositives,
          NavigableMap<Long, VariantProto> rescuedVariants) {
        this.newTruePositives = newTruePositives;
        this.removeFalsePositives = removeFalsePositives;
        this.rescuedVariants = rescuedVariants;
      }

      Multiset<VariantProto> newTruePositives() {
        return newTruePositives;
      }

      Multiset<VariantProto> removeFalsePositives() {
        return removeFalsePositives;
      }

      NavigableMap<Long, VariantProto> rescuedVariants() {
        return rescuedVariants;
      }
    }

    ContigStats rescue(FastaReader.Callback.FastaFile reference, int rescueWindowSize) {
      for (Map.Entry<Long, VariantProto> entry : Maps.newTreeMap(this.falseNegatives).entrySet()) {
        if (this.falseNegatives.containsKey(entry.getKey())) {
          VariantProto variant = entry.getValue();
          if (!VariantType.getType(variant).isStructuralVariant()) {
            RescueMission mission = RescueMission.create(reference, rescueWindowSize,
                variant, this.truePositives, this.falsePositives, this.falseNegatives);
            Multiset<VariantProto>
                newTruePositives = mission.newTruePositives(),
                removeFalsePositives = mission.removeFalsePositives();
            NavigableMap<Long, VariantProto> rescuedVariants = mission.rescuedVariants();
            for (VariantType type : VariantType.values()) {
              int newTruePositiveCount = newTruePositives.count(type),
                  removeFalsePositiveCount = removeFalsePositives.count(type);
              this.predictedVariantCounts.remove(type, removeFalsePositiveCount);
              this.falsePositiveCounts.remove(type, removeFalsePositiveCount);
              this.predictedVariantCounts.add(type, newTruePositiveCount);
              this.falseNegativeCounts.remove(type, newTruePositiveCount);
              this.truePositiveCounts.remove(type, newTruePositiveCount);
            }
            this.rescuedVariants = Optional.of(rescuedVariants);
            this.rescuedVariantCounts = Optional.of(countByType(rescuedVariants));
          }
        }
      }
      throw new UnsupportedOperationException();
    }

    public Optional<Multiset<VariantType>> rescuedVariantCounts() {
      return rescuedVariantCounts;
    }

    public Optional<NavigableMap<Long, VariantProto>> rescuedVariants() {
      return rescuedVariants;
    }

    ContigStats setKnownFalsePositives(
        NavigableMap<Long, VariantProto> knownFalsePositives,
        Iterable<Long> correctKnownFalsePositiveLocations,
        Iterable<Long> allKnownFalsePositiveLocations) {
      this.knownFalsePositives =
          Optional.of(knownFalsePositives);
      this.correctKnownFalsePositiveCounts =
          Optional.of(countByType(filter(knownFalsePositives, correctKnownFalsePositiveLocations)));
      this.allKnownFalsePositiveCounts =
          Optional.of(countByType(filter(knownFalsePositives, allKnownFalsePositiveLocations)));
      return this;
    }

    public Multiset<VariantType> truePositiveCounts() {
      return truePositiveCounts;
    }

    public NavigableMap<Long, VariantProto> truePositives() {
      return truePositives;
    }

    public Multiset<VariantType> trueVariantCounts() {
      return trueVariantCounts;
    }

    public NavigableMap<Long, VariantProto> trueVariants() {
      return trueVariants;
    }
  }

  public enum Genotype {

    HET,
    HOM_REF,
    HOM_VAR,
    NO_CALL;

    private static final Function<String, Genotype> PARSE_GENOTYPE =
        new Function<String, Genotype>() {

          private final Pattern splitPattern = Pattern.compile("(0|(?:[1-9][0-9]*?))(?:[/|]|$)");
          private final Integer zero = 0;

          @Override public Genotype apply(String genotype) {
            Integer i = null;
            for (Matcher matcher = splitPattern.matcher(genotype); matcher.matches();) {
              Integer j = Integer.parseInt(matcher.group(1));
              if (null == i) {
                i = j;
              } else if (!i.equals(j)) {
                return HET;
              }
            }
            return null == i ? NO_CALL : zero.equals(i) ? HOM_REF : HOM_VAR;
          }
        };

    public static Genotype getGenotype(VariantProto variant) {
      return GenotypeExtractor.INSTANCE.getGenotype(variant).transform(PARSE_GENOTYPE).or(NO_CALL);
    }
  }

  public static class GenotypeConcordance {

    static GenotypeConcordance create() {
      return new GenotypeConcordance();
    }

    private final Map<VariantType, Map<Genotype, Multiset<Genotype>>> concordance = new HashMap<>();

    private GenotypeConcordance() {
      for (VariantType variantType : VariantType.values()) {
        Map<Genotype, Multiset<Genotype>> map = new HashMap<>();
        for (Genotype genotype : Genotype.values()) {
          map.put(genotype, HashMultiset.<Genotype>create());
        }
        concordance.put(variantType, map);
      }
    }

    public int get(VariantType variantType, Genotype trueGenotype, Genotype predictedGenotype) {
      return concordance.get(variantType).get(trueGenotype).count(predictedGenotype);
    }

    void increment(VariantType variantType, Genotype trueGenotype, Genotype predictedGenotype) {
      concordance.get(variantType).get(trueGenotype).add(predictedGenotype);
    }
  }

  public enum VariantType {

    INDEL_DELETION(false, true, false, false, true, false, false),
    INDEL_INSERTION(false, true, false, true, false, false, false),
    INDEL_INVERSION(false, true, false, false, false, true, false),
    INDEL_OTHER(false, true, false, false, false, false, true),
    SNP(true, false, false, false, false, false, false),
    SV_DELETION(false, false, true, false, true, false, false),
    SV_INSERTION(false, false, true, true, false, false, false),
    SV_OTHER(false, false, true, false, false, false, true);

    private static String getFirstAlt(VariantProto variant) {
      return Iterables.getOnlyElement(variant.getAlternateBasesList());
    }

    public static VariantType getType(VariantProto variant) {
      return isSnp(variant)
          ? SNP
          : isStructuralVariant(variant)
              ? hasSingleAlt(variant)
                  ? isInsertion(variant)
                      ? SV_INSERTION
                      : isDeletion(variant) ? SV_DELETION : SV_OTHER
                  : SV_OTHER
              : hasSingleAlt(variant)
                  ? isInsertion(variant)
                      ? INDEL_INSERTION
                      : isDeletion(variant)
                          ? INDEL_DELETION
                          : isInversion(variant) ? INDEL_INVERSION : INDEL_OTHER
                  : INDEL_OTHER;
    }

    private static boolean hasSingleAlt(VariantProto variant) {
      return 1 == variant.getAlternateBasesCount();
    }

    private static boolean isDeletion(VariantProto variant) {
      return getFirstAlt(variant).length() < variant.getReferenceBases().length();
    }

    private static boolean isInsertion(VariantProto variant) {
      return variant.getReferenceBases().length() < getFirstAlt(variant).length();
    }

    private static boolean isInversion(VariantProto variant) {
      String ref = variant.getReferenceBases(), alt = getFirstAlt(variant);
      int length = ref.length();
      if (length == alt.length()) {
        for (int i = 0; i < length; ++i) {
          if (ref.charAt(i) != alt.charAt(length - i - 1)) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    private static boolean isSnp(VariantProto variant) {
      if (1 == variant.getReferenceBases().length()) {
        for (String alternateBases : variant.getAlternateBasesList()) {
          if (1 != alternateBases.length()) {
            return false;
          }
        }
        return true;
      }
      return false;
    }

    private static boolean isStructuralVariant(VariantProto variant) {
      for (VariantProto.Multimap.Entry entry : variant.getInfo().getEntryList()) {
        if ("SVTYPE".equals(entry.getKey())) {
          return true;
        }
      }
      return false;
    }

    private final boolean isDeletion;
    private final boolean isIndel;
    private final boolean isInsertion;
    private final boolean isInversion;
    private final boolean isOther;
    private final boolean isSnp;
    private final boolean isStructuralVariant;

    private VariantType(
        boolean isSnp,
        boolean isIndel,
        boolean isStructuralVariant,
        boolean isInsertion,
        boolean isDeletion,
        boolean isInversion,
        boolean isOther) {
      this.isSnp = isSnp;
      this.isIndel = isIndel;
      this.isStructuralVariant = isStructuralVariant;
      this.isInsertion = isInsertion;
      this.isDeletion = isDeletion;
      this.isInversion = isInversion;
      this.isOther = isOther;
    }

    public boolean isDeletion() {
      return isDeletion;
    }

    public boolean isIndel() {
      return isIndel;
    }

    public boolean isInsertion() {
      return isInsertion;
    }

    public boolean isInversion() {
      return isInversion;
    }

    public boolean isOther() {
      return isOther;
    }

    public boolean isSnp() {
      return isSnp;
    }

    public boolean isStructuralVariant() {
      return isStructuralVariant;
    }
  }

  private static final Function<VariantProto, Long> GET_POSITION =
      new Function<VariantProto, Long>() {
        @Override public Long apply(VariantProto variant) {
          return variant.getPosition();
        }
      };

  private static final Function<Iterable<VariantProto>, Multimap<String, VariantProto>>
      INDEX_BY_CONTIG =
      new Function<Iterable<VariantProto>, Multimap<String, VariantProto>>() {

        private final Function<VariantProto, String> getContig =
            new Function<VariantProto, String>() {
              @Override public String apply(VariantProto variant) {
                return variant.getContig();
              }
            };

        @Override public Multimap<String, VariantProto> apply(Iterable<VariantProto> variants) {
          return Multimaps.index(variants, getContig);
        }
      };

  private static final Function<Iterable<VariantProto>, NavigableMap<Long, VariantProto>>
      INDEX_BY_POSITION = uniqueIndex(
          new Function<VariantProto, Long>() {
            @Override public Long apply(VariantProto variant) {
              return variant.getPosition();
            }
          });

  private static final Function<Multimap<String, ?>, Set<String>>
      KEY_SET = keySetFunction();

  public static Builder builder() {
    return new Builder();
  }

  private static <X extends Comparable<? super X>> NavigableSet<X>
      difference(Set<? extends X> lhs, Set<? extends X> rhs) {
    NavigableSet<X> difference = new TreeSet<>();
    difference.addAll(lhs);
    difference.removeAll(rhs);
    return difference;
  }

  @SafeVarargs
  private static <X extends Comparable<? super X>> NavigableSet<X>
      intersection(Set<? extends X>... sets) {
    NavigableSet<X> intersection = new TreeSet<>();
    Collection<Set<? extends X>> collection = Arrays.asList(sets);
    for (Set<? extends X> set : Iterables.limit(collection, 1)) {
      intersection.addAll(set);
    }
    for (Set<? extends X> set : Iterables.skip(collection, 1)) {
      intersection.retainAll(set);
    }
    return intersection;
  }

  private static <X> Function<Multimap<X, ?>, Set<X>> keySetFunction() {
    return new Function<Multimap<X, ?>, Set<X>>() {
          @Override public Set<X> apply(Multimap<X, ?> multimap) {
            return multimap.keySet();
          }
        };
  }

  @SafeVarargs
  private static <X extends Comparable<? super X>> NavigableSet<X>
      union(Set<? extends X>... sets) {
    NavigableSet<X> union = new TreeSet<>();
    for (Set<? extends X> set : sets) {
      union.addAll(set);
    }
    return union;
  }

  private static <X extends Comparable<? super X>, Y> Function<Iterable<Y>, NavigableMap<X, Y>>
      uniqueIndex(final Function<? super Y, X> function) {
    return new Function<Iterable<Y>, NavigableMap<X, Y>>() {
          @Override public NavigableMap<X, Y> apply(Iterable<Y> iterable) {
            NavigableMap<X, Y> result = new TreeMap<>();
            for (Y object : iterable) {
              result.put(function.apply(object), object);
            }
            return result;
          }
        };
  }

  private final int maxIndelLength;
  private final int maxSvBreakpointDistance;
  private final int maxVariantLengthDifference;
  private final Optional<FastaReader.Callback.FastaFile> reference;
  private final int rescueWindowSize;

  private VariantEvaluator(
      Optional<FastaReader.Callback.FastaFile> reference,
      int maxIndelLength,
      int maxSvBreakpointDistance,
      int maxVariantLengthDifference,
      int rescueWindowSize) {
    this.reference = reference;
    this.maxIndelLength = maxIndelLength;
    this.maxSvBreakpointDistance = maxSvBreakpointDistance;
    this.maxVariantLengthDifference = maxVariantLengthDifference;
    this.rescueWindowSize = rescueWindowSize;
  }

  public Map<String, ContigStats> evaluate(
      Iterable<VariantProto> trueVariants,
      Iterable<VariantProto> predictedVariants) {
    return evaluate(trueVariants, predictedVariants, Optional.<Iterable<VariantProto>>absent());
  }

  public Map<String, ContigStats> evaluate(
      Iterable<VariantProto> trueVariants,
      Iterable<VariantProto> predictedVariants,
      Iterable<VariantProto> knownFalsePositives) {
    return evaluate(trueVariants, predictedVariants, Optional.of(knownFalsePositives));
  }

  private Map<String, ContigStats> evaluate(
      Iterable<VariantProto> trueVariants,
      Iterable<VariantProto> predictedVariants,
      Optional<Iterable<VariantProto>> knownFalsePositives) {
    Multimap<String, VariantProto>
        trueVars = INDEX_BY_CONTIG.apply(trueVariants),
        predVars = INDEX_BY_CONTIG.apply(predictedVariants);
    Optional<Multimap<String, VariantProto>>
        knownFps = knownFalsePositives.transform(INDEX_BY_CONTIG);
    ImmutableMap.Builder<String, ContigStats> result = ImmutableMap.builder();
    for (final String contig : union(
        KEY_SET.apply(trueVars),
        KEY_SET.apply(predVars),
        knownFps.transform(KEY_SET).or(Collections.<String>emptySet()))) {
      Function<Multimap<String, VariantProto>, NavigableMap<Long, VariantProto>> indexByPosition =
          Functions.compose(
              INDEX_BY_POSITION,
              new Function<Multimap<String, VariantProto>, Iterable<VariantProto>>() {
                @Override public Iterable<VariantProto> apply(
                    Multimap<String, VariantProto> multimap) {
                  return multimap.get(contig);
                }
              });
      result.put(
          contig,
          evaluate(
              contig,
              indexByPosition.apply(trueVars),
              indexByPosition.apply(predVars),
              knownFps.transform(indexByPosition)));
    }
    return result.build();
  }

  private ContigStats evaluate(
      String contig,
      NavigableMap<Long, VariantProto> trueVariants,
      NavigableMap<Long, VariantProto> predictedVariants,
      Optional<NavigableMap<Long, VariantProto>> knownFalsePositives) {
    Set<Long>
        trueVariantLocations = trueVariants.keySet(),
        predictedVariantLocations = predictedVariants.keySet(),
        truePositiveLocations = new HashSet<>(),
        falsePositiveLocations = difference(predictedVariantLocations, trueVariantLocations),
        falseNegativeLocations = difference(trueVariantLocations, predictedVariantLocations),
        incorrectPredictions = new HashSet<>(),
        correctKnownFalsePositiveLocations = new HashSet<>(),
        allKnownFalsePositiveLocations = new HashSet<>();
    GenotypeConcordance concordance = GenotypeConcordance.create();
    boolean knownFalsePositivesPresent = knownFalsePositives.isPresent();
    for (Long location : intersection(predictedVariantLocations, trueVariantLocations)) {
      VariantProto
          trueVariant = trueVariants.get(location),
          predictedVariant = predictedVariants.get(location);
      VariantType trueVariantType = VariantType.getType(trueVariant);
      if (trueVariantType == VariantType.getType(predictedVariant)
          && (trueVariantType.isStructuralVariant() ||trueVariant.getAlternateBasesList()
              .equals(predictedVariant.getAlternateBasesList()))) {
        truePositiveLocations.add(location);
        concordance.increment(
            trueVariantType,
            Genotype.getGenotype(trueVariant),
            Genotype.getGenotype(predictedVariant));
      } else {
        incorrectPredictions.add(location);
      }
    }
    if (knownFalsePositivesPresent) {
      NavigableMap<Long, VariantProto> knownFp = knownFalsePositives.get();
      for (Long location :
          intersection(predictedVariantLocations, knownFp.keySet())) {
        VariantProto knownFalsePositive = knownFp.get(location);
        if (Objects.equal(
            knownFalsePositive.getReferenceBases(),
            predictedVariants.get(location).getReferenceBases())) {
          correctKnownFalsePositiveLocations.add(location);
        }
        allKnownFalsePositiveLocations.add(location);
      }
    }
    for (Long location : difference(predictedVariantLocations, trueVariantLocations)) {
      VariantProto trueVariant = trueVariants.get(location);
      VariantType trueVariantType = VariantType.getType(trueVariant);
      if (trueVariantType.isStructuralVariant()) {
        Optional<Long> structuralMatch = structuralMatch(trueVariant, predictedVariants);
        if (structuralMatch.isPresent()) {
          Long match = structuralMatch.get();
          if (falsePositiveLocations.contains(match)) {
            truePositiveLocations.add(location);
            falsePositiveLocations.remove(match);
            falseNegativeLocations.remove(location);
            concordance.increment(
                trueVariantType,
                Genotype.getGenotype(trueVariant),
                Genotype.getGenotype(predictedVariants.get(match)));
          }
        }
      }
    }
    falsePositiveLocations.addAll(incorrectPredictions);
    falseNegativeLocations.addAll(incorrectPredictions);
    ContigStats variantStats = ContigStats.create(contig, trueVariants, predictedVariants,
        truePositiveLocations, falsePositiveLocations, falseNegativeLocations, incorrectPredictions,
        concordance);
    if (reference.isPresent()) {
      variantStats.rescue(reference.get(), rescueWindowSize);
    }
    if (knownFalsePositivesPresent) {
      variantStats.setKnownFalsePositives(
          knownFalsePositives.get(),
          correctKnownFalsePositiveLocations,
          allKnownFalsePositiveLocations);
    }
    return variantStats;
  }

  private Optional<Long> structuralMatch(
      final VariantProto trueVariant,
      NavigableMap<Long, VariantProto> predictedVariants) {
    final long trueVariantPosition = trueVariant.getPosition();
    Collection<VariantProto> candidates = FluentIterable
        .from(predictedVariants
            .subMap(
                trueVariantPosition - maxSvBreakpointDistance, true,
                trueVariantPosition + maxSvBreakpointDistance, true)
            .values())
        .filter(
            new Predicate<VariantProto>() {

              private final VariantType trueVariantType = VariantType.getType(trueVariant);

              @Override public boolean apply(VariantProto predictedVariant) {
                if (trueVariantType == VariantType.getType(predictedVariant)) {
                  int trueReferenceLength = trueVariant.getReferenceBases().length(),
                      predictedReferenceLength = predictedVariant.getReferenceBases().length();
                  for (String trueAlt : trueVariant.getAlternateBasesList()) {
                    int trueGain = trueReferenceLength - trueAlt.length();
                    for (String predictedAlt : predictedVariant.getAlternateBasesList()) {
                      int predGain = predictedReferenceLength - predictedAlt.length();
                      if (trueGain - maxVariantLengthDifference < predGain
                          && predGain < trueGain + maxVariantLengthDifference) {
                        return true;
                      }
                    }
                  }
                }
                return false;
              }
            })
        .toList();
    Queue<VariantProto> priorityQueue = new PriorityQueue<>(Math.max(candidates.size(), 1),
        new Comparator<VariantProto>() {
          @Override public int compare(VariantProto lhs, VariantProto rhs) {
            return Long.compare(
                Math.abs(trueVariantPosition - lhs.getPosition()),
                Math.abs(trueVariantPosition - rhs.getPosition()));
          }
        });
    priorityQueue.addAll(candidates);
    return Optional.fromNullable(priorityQueue.peek()).transform(GET_POSITION);
  }
}
