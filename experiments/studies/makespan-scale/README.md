# Makespan-scale derived inputs

## Purpose and evidence boundary

The three 5,000-task DAX files in `inputs/` are deterministic composite workflows constructed from the corresponding 1,000-task Pegasus DAX inputs. Each composite contains **five independent copies** of its source DAG. Task runtimes, file sizes, and dependency structure are preserved within each copy.

These files are controlled scaling inputs, not newly collected scientific workflows and not a new empirical corpus. They must not be described as real 5,000-task Montage, CyberShake, or Inspiral instances, or used as five independent statistical samples of a workflow population.

| Source DAX | Derived DAX | Jobs per copy | Copies | Total jobs | Dependency edges |
| --- | --- | ---: | ---: | ---: | ---: |
| `datasets/dax/montage/n1000/Montage_1000.dax` | `inputs/Montage_5000.dax` | 1,000 | 5 | 5,000 | 4,170 |
| `datasets/dax/cybershake/n1000/CyberShake_1000.dax` | `inputs/CyberShake_5000.dax` | 1,000 | 5 | 5,000 | 4,980 |
| `datasets/dax/inspiral/n1000/Inspiral_1000.dax` | `inputs/Inspiral_5000.dax` | 1,000 | 5 | 5,000 | 3,855 |

## Construction procedure

For each source file, the generator parses the DAX as XML and creates one output root with the source root attributes. It then performs the following steps for replica indices `r0` through `r4`:

1. Deep-copy every source job and dependency element, preserving its attributes and intra-copy ordering.
2. Append `_r0`, `_r1`, `_r2`, `_r3`, or `_r4` to every `id`, `ref`, `file`, and `name` attribute in the copied elements. This qualifies job identifiers, job type names, logical filenames, and dependency references by replica.
3. Append the five replica groups to the same output root without adding any dependency between groups.

Consequently, job identifiers and logical filenames are disjoint across replicas, and every parent/child reference resolves to a job carrying the same replica suffix. There are no cross-replica job, file, or edge bindings.

The root `count` and `jobCount` attributes are set to 5,000. The root `childCount` is five times the source dependency count: 4,170 for Montage, 4,980 for CyberShake, and 3,855 for Inspiral. Source files under `datasets/` are read-only inputs to this procedure and are not rewritten.

The derived files are referenced by `experiments/configs/makespan-static-scale.json` and `experiments/configs/makespan-online-scale.json` alongside their 1,000-task sources so that each source/composite pair can be run under the same platform and algorithm declarations without mixing static planning and online scheduling in one experiment.
