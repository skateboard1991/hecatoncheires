package com.skateboard.hecatoncheires.checktools;

import static com.android.SdkConstants.DOT_XML;
import static com.android.tools.lint.gradle.SyncOptions.createOutputPath;
import static com.android.tools.lint.gradle.SyncOptions.validateOutputFile;
import static com.google.common.base.Preconditions.checkNotNull;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.LintOptions;
import com.android.builder.model.Variant;
import com.android.tools.lint.LintCliFlags;
import com.android.tools.lint.Reporter;
import com.android.tools.lint.TextReporter;
import com.android.tools.lint.Warning;
import com.android.tools.lint.checks.BuiltinIssueRegistry;
import com.android.tools.lint.checks.GradleDetector;
import com.android.tools.lint.checks.UnusedResourceDetector;
import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.LintBaseline;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.LintUtils;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.gradle.GroovyGradleDetector;
import com.android.tools.lint.gradle.LintGradleClient;
import com.android.tools.lint.gradle.NonAndroidIssueRegistry;
import com.android.tools.lint.gradle.SyncOptions;
import com.android.tools.lint.gradle.api.LintExecutionRequest;
import com.android.tools.lint.gradle.api.VariantInputs;
import com.android.utils.Pair;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

/**
 * Class responsible for driving lint from within Gradle.
 * The purpose of this class is to isolate all lint API access to this
 * single class, such that Gradle can load this driver in its own
 * class loader and thereby have lint itself run in its own
 * class loader, such that classes in the Gradle plugins (such as
 * the Kotlin compiler) does not interfere with classes used by lint
 * (such as a different bundled version of the Kotlin compiler.)
 */
@SuppressWarnings("unused") // Used vi reflection from LintExecutionRequest
public class IncrementLintGradleExecution {
    private final LintExecutionRequest descriptor;

    public IncrementLintGradleExecution(LintExecutionRequest descriptor) {
        this.descriptor = descriptor;
    }

    // Along with the constructor, the only public access into this class,
    // intended to be used via reflection. Everything else should be private:
    @SuppressWarnings("unused") // Used via reflection from ReflectiveLintRunner
    public void analyze() throws IOException {
        ToolingModelBuilderRegistry toolingRegistry = descriptor.getToolingRegistry();
        if (toolingRegistry != null) {
            AndroidProject modelProject = createAndroidProject(descriptor.getProject(),
                    toolingRegistry);
            String variantName = descriptor.getVariantName();

            if (variantName != null) {
                for (Variant variant : modelProject.getVariants()) {
                    if (variant.getName().equals(variantName)) {
                        lintSingleVariant(variant);
                        return;
                    }
                }
            } else { // All variants
                lintAllVariants(modelProject);
            }
        } else {
            // Not applying the Android Gradle plugin
            lintNonAndroid();
        }
    }

    @Nullable
    private LintOptions getLintOptions() {
        return descriptor.getLintOptions();
    }

    @Nullable
    private File getSdkHome() {
        return descriptor.getSdkHome();
    }

    private boolean isFatalOnly() {
        return descriptor.isFatalOnly();
    }

    @Nullable
    private File getReportsDir() {
        return descriptor.getReportsDir();
    }

    private void abort(
            @Nullable LintGradleClient client,
            @Nullable List<Warning> warnings,
            boolean isAndroid) {
        String message;
        if (isAndroid) {
            if (isFatalOnly()) {
                message = ""
                        + "Lint found fatal errors while assembling a release target.\n"
                        + "\n"
                        + "To proceed, either fix the issues identified by lint, or modify your build script as follows:\n"
                        + "...\n"
                        + "android {\n"
                        + "    lintOptions {\n"
                        + "        checkReleaseBuilds false\n"
                        + "        // Or, if you prefer, you can continue to check for errors in release builds,\n"
                        + "        // but continue the build even when errors are found:\n"
                        + "        abortOnError false\n"
                        + "    }\n"
                        + "}\n"
                        + "...";
            } else {
                message = ""
                        + "Lint found errors in the project; aborting build.\n"
                        + "\n"
                        + "Fix the issues identified by lint, or add the following to your build script to proceed with errors:\n"
                        + "...\n"
                        + "android {\n"
                        + "    lintOptions {\n"
                        + "        abortOnError false\n"
                        + "    }\n"
                        + "}\n"
                        + "...";
            }
        } else {
            message = ""
                    + "Lint found errors in the project; aborting build.\n"
                    + "\n"
                    + "Fix the issues identified by lint, or add the following to your build script to proceed with errors:\n"
                    + "...\n"
                    + "lintOptions {\n"
                    + "    abortOnError false\n"
                    + "}\n"
                    + "...";
        }

        if (warnings != null && client != null &&
                // See if there's at least one text reporter
                client.getFlags().getReporters().stream().
                        noneMatch(reporter -> reporter instanceof TextReporter)) {
            List<Warning> errors=new ArrayList<>();
            for(Warning warning:warnings){
                if(warning.severity.isError()){
                    errors.add(warning);
                }
            }
            if (!errors.isEmpty()) {
                String prefix = "Errors found:\n\n";
                if (errors.size() > 3) {
                    // Truncate
                    prefix = "The first 3 errors (out of " + errors.size() + ") were:\n";
                    errors = Arrays.asList(errors.get(0), errors.get(1), errors.get(2));
                }
                StringWriter writer = new StringWriter();
                LintCliFlags flags = client.getFlags();
                flags.setExplainIssues(false);
                TextReporter reporter = Reporter
                        .createTextReporter(client, flags, null, writer, false);
                try {
                    Reporter.Stats stats = new Reporter.Stats(errors.size(), 0);
                    reporter.setWriteStats(false);
                    reporter.write(stats, errors);
                    message += "\n\n" + prefix + writer.toString();
                } catch (IOException ignore) {
                }
            }
        }

        throw new GradleException(message);
    }

    /**
     * Runs lint on the given variant and returns the set of warnings
     */
    private Pair<List<Warning>, LintBaseline> runLint(
            @Nullable Variant variant,
            @NonNull VariantInputs variantInputs,
            boolean report, boolean isAndroid) {
        IssueRegistry registry = createIssueRegistry(isAndroid);
        LintCliFlags flags = new LintCliFlags();
        LintGradleClient client =
                new IncrementLintGradleClient(
                        descriptor.getGradlePluginVersion(),
                        registry,
                        flags,
                        descriptor.getProject(),
                        descriptor.getSdkHome(),
                        variant,
                        variantInputs,
                        descriptor.getBuildTools(),
                        isAndroid);
        boolean fatalOnly = descriptor.isFatalOnly();
        if (fatalOnly) {
            flags.setFatalOnly(true);
        }
        LintOptions lintOptions = descriptor.getLintOptions();
        if (lintOptions != null) {
            syncOptions(
                    lintOptions,
                    client,
                    flags,
                    variant,
                    descriptor.getProject(),
                    descriptor.getReportsDir(),
                    report,
                    fatalOnly);
        } else {
            // Set up some default reporters
            flags.getReporters().add(Reporter.createTextReporter(client, flags, null,
                    new PrintWriter(System.out, true), false));
            File html = validateOutputFile(createOutputPath(descriptor.getProject(), null, ".html",
                    null, flags.isFatalOnly()));
            File xml = validateOutputFile(createOutputPath(descriptor.getProject(), null, DOT_XML,
                    null, flags.isFatalOnly()));
            try {
                flags.getReporters().add(Reporter.createHtmlReporter(client, html, flags));
                flags.getReporters().add(Reporter.createXmlReporter(client, xml, false));
            } catch (IOException e) {
                throw new GradleException(e.getMessage(), e);
            }
        }
        if (!report || fatalOnly) {
            flags.setQuiet(true);
        }
        flags.setWriteBaselineIfMissing(report && !fatalOnly);

        Pair<List<Warning>, LintBaseline> warnings;
        try {
            warnings = client.run(registry);
            if (warnings == null || warnings.getFirst().size() <= 0) {
                System.out.println("lint no issues found");
            }
        } catch (IOException e) {
            throw new GradleException("Invalid arguments.", e);
        }

        if (report && client.haveErrors() && flags.isSetExitCode()) {
            abort(client, warnings.getFirst(), isAndroid);
        }

        return warnings;
    }

    private static void syncOptions(
            @Nullable LintOptions options,
            @NonNull LintGradleClient client,
            @NonNull LintCliFlags flags,
            @Nullable Variant variant,
            @NonNull Project project,
            @Nullable File reportsDir,
            boolean report,
            boolean fatalOnly) {
        if (options != null) {
            SyncOptions.syncTo(options,
                    client,
                    flags,
                    variant != null ? variant.getName() : null,
                    project,
                    reportsDir,
                    report);
        }

        boolean displayEmpty = !(fatalOnly || flags.isQuiet());
        for (Reporter reporter : flags.getReporters()) {
            reporter.setDisplayEmpty(displayEmpty);
        }
    }

    protected static AndroidProject createAndroidProject(@NonNull Project gradleProject,
                                                         @NonNull ToolingModelBuilderRegistry toolingRegistry) {
        String modelName = AndroidProject.class.getName();
        ToolingModelBuilder modelBuilder = toolingRegistry.getBuilder(modelName);

        // setup the level 3 sync.
        final ExtraPropertiesExtension ext = gradleProject.getExtensions().getExtraProperties();
        ext.set(
                AndroidProject.PROPERTY_BUILD_MODEL_ONLY_VERSIONED,
                Integer.toString(AndroidProject.MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD));
        ext.set(AndroidProject.PROPERTY_BUILD_MODEL_DISABLE_SRC_DOWNLOAD, true);

        try {
            return (AndroidProject) modelBuilder.buildAll(modelName, gradleProject);
        } finally {
            ext.set(AndroidProject.PROPERTY_BUILD_MODEL_ONLY_VERSIONED, null);
            ext.set(AndroidProject.PROPERTY_BUILD_MODEL_DISABLE_SRC_DOWNLOAD, null);
        }
    }

    private static BuiltinIssueRegistry createIssueRegistry(boolean isAndroid) {
        if (isAndroid) {
            return new LintGradleIssueRegistry();
        } else {
            return new NonAndroidIssueRegistry();
        }
    }

    // Issue registry when Lint is run inside Gradle: we replace the Gradle
    // detector with a local implementation which directly references Groovy
    // for parsing. In Studio on the other hand, the implementation is replaced
    // by a PSI-based check. (This is necessary for now since we don't have a
    // tool-agnostic API for the Groovy AST and we don't want to add a 6.3MB dependency
    // on Groovy itself quite yet.
    private static class LintGradleIssueRegistry extends BuiltinIssueRegistry {
        private boolean mInitialized;

        public LintGradleIssueRegistry() {
        }

        @NonNull
        @Override
        public List<Issue> getIssues() {
            List<Issue> issues = super.getIssues();
            if (!mInitialized) {
                mInitialized = true;
                for (Issue issue : issues) {
                    if (issue.getImplementation().getDetectorClass() == GradleDetector.class) {
                        issue.setImplementation(GroovyGradleDetector.IMPLEMENTATION);
                    }
                }
            }

            return issues;
        }

        @Override
        public int getApi() {
            return ApiKt.CURRENT_API;
        }
    }

    /**
     * Runs lint on a single specified variant
     */
    public void lintSingleVariant(@NonNull Variant variant) {
        VariantInputs variantInputs = descriptor.getVariantInputs(variant.getName());
        if (variantInputs != null) {
            runLint(variant, variantInputs, true, true);
        }
    }

    /**
     * Runs lint for a non-Android project (such as a project that only applies the
     * Kotlin Gradle plugin, not the Android Gradle plugin
     */
    public void lintNonAndroid() {
        VariantInputs variantInputs = descriptor.getVariantInputs("");
        if (variantInputs != null) {
            runLint(null, variantInputs, true, false);
        }
    }

    /**
     * Runs lint individually on all the variants, and then compares the results across variants and
     * reports these
     */
    public void lintAllVariants(@NonNull AndroidProject modelProject) throws IOException {
        // In the Gradle integration we iterate over each variant, and
        // attribute unused resources to each variant, so don't make
        // each variant run go and inspect the inactive variant sources
        UnusedResourceDetector.sIncludeInactiveReferences = false;

        Map<Variant, List<Warning>> warningMap = Maps.newHashMap();
        List<LintBaseline> baselines = Lists.newArrayList();
        for (Variant variant : modelProject.getVariants()) {
            // we are not running lint on all the variants, so skip the ones where we don't have
            // a variant inputs (see TaskManager::isLintVariant)
            final VariantInputs variantInputs = descriptor.getVariantInputs(variant.getName());
            if (variantInputs != null) {
                Pair<List<Warning>, LintBaseline> pair =
                        runLint(variant, variantInputs, false, true);
                List<Warning> warnings = pair.getFirst();
                warningMap.put(variant, warnings);
                LintBaseline baseline = pair.getSecond();
                if (baseline != null) {
                    baselines.add(baseline);
                }
            }
        }

        final LintOptions lintOptions = getLintOptions();

        // Compute error matrix
        boolean quiet = false;
        if (lintOptions != null) {
            quiet = lintOptions.isQuiet();
        }

        for (Map.Entry<Variant, List<Warning>> entry : warningMap.entrySet()) {
            Variant variant = entry.getKey();
            List<Warning> warnings = entry.getValue();
            if (!isFatalOnly() && !quiet) {
                descriptor.warn(
                        "Ran lint on variant {}: {} issues found",
                        variant.getName(),
                        warnings.size());
            }
        }

        List<Warning> mergedWarnings = LintGradleClient.merge(warningMap, modelProject);
        int errorCount = 0;
        int warningCount = 0;
        for (Warning warning : mergedWarnings) {
            if (warning.severity == Severity.ERROR || warning.severity == Severity.FATAL) {
                errorCount++;
            } else if (warning.severity == Severity.WARNING) {
                warningCount++;
            }
        }

        // We pick the first variant to generate the full report and don't generate if we don't
        // have any variants.
        if (!modelProject.getVariants().isEmpty()) {
            Set<Variant> allVariants =new TreeSet<>(checkNotNull(Comparator.comparing(Variant::getName)));
            allVariants.addAll(modelProject.getVariants());
            Variant variant = allVariants.iterator().next();

            IssueRegistry registry = new BuiltinIssueRegistry();
            LintCliFlags flags = new LintCliFlags();
            VariantInputs variantInputs = descriptor.getVariantInputs(variant.getName());
            assert variantInputs != null : variant.getName();
            LintGradleClient client = new IncrementLintGradleClient(
                    descriptor.getGradlePluginVersion(),
                    registry,
                    flags,
                    descriptor.getProject(),
                    getSdkHome(),
                    variant,
                    variantInputs,
                    descriptor.getBuildTools(),
                    true);
            syncOptions(
                    lintOptions,
                    client,
                    flags,
                    null,
                    descriptor.getProject(),
                    getReportsDir(),
                    true,
                    isFatalOnly());

            // Compute baseline counts. This is tricky because an error could appear in
            // multiple variants, and in that case it should only be counted as filtered
            // from the baseline once, but if there are errors that appear only in individual
            // variants, then they shouldn't count as one. To correctly account for this we
            // need to ask the baselines themselves to merge their results. Right now they
            // only contain the remaining (fixed) issues; to address this we'd need to move
            // found issues to a different map such that at the end we can successively
            // merge the baseline instances together to a final one which has the full set
            // of filtered and remaining counts.
            int baselineErrorCount = 0;
            int baselineWarningCount = 0;
            int fixedCount = 0;
            if (!baselines.isEmpty()) {
                // Figure out the actual overlap; later I could stash these into temporary
                // objects to compare
                // For now just combine them in a dumb way
                for (LintBaseline baseline : baselines) {
                    baselineErrorCount =
                            Math.max(baselineErrorCount, baseline.getFoundErrorCount());
                    baselineWarningCount =
                            Math.max(baselineWarningCount, baseline.getFoundWarningCount());
                    fixedCount = Math.max(fixedCount, baseline.getFixedCount());
                }
            }

            Reporter.Stats stats =
                    new Reporter.Stats(
                            errorCount,
                            warningCount,
                            baselineErrorCount,
                            baselineWarningCount,
                            fixedCount);

            for (Reporter reporter : flags.getReporters()) {
                reporter.write(stats, mergedWarnings);
            }

            File baselineFile = flags.getBaselineFile();
            if (baselineFile != null && !baselineFile.exists()) {
                File dir = baselineFile.getParentFile();
                boolean ok = true;
                if (!dir.isDirectory()) {
                    ok = dir.mkdirs();
                }
                if (!ok) {
                    System.err.println("Couldn't create baseline folder " + dir);
                } else {
                    Reporter reporter = Reporter.createXmlReporter(client, baselineFile, true);
                    reporter.write(stats, mergedWarnings);
                    System.err.println("Created baseline file " + baselineFile);
                    if (LintGradleClient.continueAfterBaseLineCreated()) {
                        return;
                    }
                    System.err.println("(Also breaking build in case this was not intentional.)");
                    String message =
                            ""
                                    + "Created baseline file "
                                    + baselineFile
                                    + "\n"
                                    + "\n"
                                    + "Also breaking the build in case this was not intentional. If you\n"
                                    + "deliberately created the baseline file, re-run the build and this\n"
                                    + "time it should succeed without warnings.\n"
                                    + "\n"
                                    + "If not, investigate the baseline path in the lintOptions config\n"
                                    + "or verify that the baseline file has been checked into version\n"
                                    + "control.\n"
                                    + "\n"
                                    + "You can set the system property lint.baselines.continue=true\n"
                                    + "if you want to create many missing baselines in one go.";
                    throw new GradleException(message);
                }
            }

            if (baselineErrorCount > 0 || baselineWarningCount > 0) {
                System.out.println(
                        String.format(
                                "%1$s were filtered out because "
                                        + "they were listed in the baseline file, %2$s\n",
                                LintUtils.describeCounts(
                                        baselineErrorCount, baselineWarningCount, false, true),
                                baselineFile));
            }
            if (fixedCount > 0) {
                System.out.println(
                        String.format(Locale.US,
                                "%1$d errors/warnings were listed in the "
                                        + "baseline file (%2$s) but not found in the project; perhaps they have "
                                        + "been fixed?\n",
                                fixedCount, baselineFile));
            }

            if (flags.isSetExitCode() && errorCount > 0) {
                abort(client, mergedWarnings, true);
            }
        }
    }
}
