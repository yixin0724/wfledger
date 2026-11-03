/**
 * Matrix, execution entry points, and explicit input identity contract of the P7 frozen
 * reference baseline.
 *
 * <p>This package is not part of the public algorithm API of the core simulator. It is
 * built only under {@code -Pexperiments}, requires the caller to supply an absolute
 * {@code datasets} root directory and an empty output directory explicitly, and writes its
 * own driver, protocol, and input root into the v3 evidence bundle. The experimental
 * boundary of P7 and the scope of what it cannot claim are governed by the project
 * documentation.</p>
 */
package org.workflowsim.experiments.reference.p7;
