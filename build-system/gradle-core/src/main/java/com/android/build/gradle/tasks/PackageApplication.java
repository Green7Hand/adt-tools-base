/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.tasks;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.build.gradle.AndroidGradleOptions;
import com.android.build.gradle.internal.incremental.InstantRunBuildContext;
import com.android.build.gradle.internal.scope.ConventionMappingHelper;
import com.android.build.gradle.internal.scope.VariantOutputScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.builder.packaging.DuplicateFileException;
import com.android.ide.common.res2.FileStatus;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.ParallelizableTask;
import org.gradle.tooling.BuildException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Task to package an Android application (APK).
 */
@ParallelizableTask
public class PackageApplication extends PackageAndroidArtifact {
    /**
     * If {@code true}, the tasks works with the old code.
     */
    private boolean inOldMode;

    @Override
    protected boolean isIncremental() {
        if (inOldMode) {
            return false;
        }
        return true;
    }

    @Override
    protected void doFullTaskAction() throws IOException {
        if (inOldMode) {
            doOldTask();
            return;
        }
        super.doFullTaskAction();
    }

    @Override
    protected void doIncrementalTaskAction(Map<File, FileStatus> changedInputs) throws IOException {
        if (inOldMode) {
            doFullTaskAction();
            return;
        }
        super.doIncrementalTaskAction(changedInputs);
    }

    /**
     * Old packaging code.
     */
    private void doOldTask() {
        // if the blocker file is there, do not run.
        if (getMarkerFile().exists()) {
            try {
                if (MarkerFile.readMarkerFile(getMarkerFile()) == MarkerFile.Command.BLOCK) {
                    return;
                }
            } catch (IOException e) {
                getLogger().warn("Cannot read marker file, proceed with execution", e);
            }
        }

        try {

            ImmutableSet.Builder<File> dexFoldersForApk = ImmutableSet.builder();
            ImmutableList.Builder<File> javaResourcesForApk = ImmutableList.builder();

            Collection<File> javaResourceFiles = getJavaResourceFiles();
            if (javaResourceFiles != null) {
                javaResourcesForApk.addAll(javaResourceFiles);
            }
            switch(dexPackagingPolicy) {
                case INSTANT_RUN:
                    File zippedDexes = zipDexesForInstantRun(getDexFolders(), dexFoldersForApk);
                    javaResourcesForApk.add(zippedDexes);
                    break;
                case STANDARD:
                    dexFoldersForApk.addAll(getDexFolders());
                    break;
                default:
                    throw new RuntimeException(
                            "Unhandled DexPackagingPolicy : " + getDexPackagingPolicy());
            }

            getBuilder().oldPackageApk(
                    getResourceFile().getAbsolutePath(),
                    dexFoldersForApk.build(),
                    javaResourcesForApk.build(),
                    getJniFolders(),
                    getAbiFilters(),
                    getJniDebugBuild(),
                    getSigningConfig(),
                    getOutputFile(),
                    getMinSdkVersion());
        } catch (DuplicateFileException e) {
            Logger logger = getLogger();
            logger.error("Error: duplicate files during packaging of APK " + getOutputFile()
                    .getAbsolutePath());
            logger.error("\tPath in archive: " + e.getArchivePath());
            int index = 1;
            for (File file : e.getSourceFiles()) {
                logger.error("\tOrigin " + (index++) + ": " + file);
            }
            logger.error("You can ignore those files in your build.gradle:");
            logger.error("\tandroid {");
            logger.error("\t  packagingOptions {");
            logger.error("\t    exclude \'" + e.getArchivePath() + "\'");
            logger.error("\t  }");
            logger.error("\t}");
            throw new BuildException(e.getMessage(), e);
        } catch (Exception e) {
            //noinspection ThrowableResultOfMethodCallIgnored
            Throwable rootCause = Throwables.getRootCause(e);
            if (rootCause instanceof NoSuchAlgorithmException) {
                throw new BuildException(
                        rootCause.getMessage() + ": try using a newer JVM to build your application.",
                        rootCause);
            }
            throw new BuildException(e.getMessage(), e);
        }
        // mark this APK production, this will eventually be saved when instant-run is enabled.
        // this might get overridden if the package is signed/aligned.
        try {
            instantRunContext.addChangedFile(InstantRunBuildContext.FileType.MAIN,
                    getOutputFile());
        } catch (IOException e) {
            throw new BuildException(e.getMessage(), e);
        }
    }

    private File zipDexesForInstantRun(Iterable<File> dexFolders,
            ImmutableSet.Builder<File> dexFoldersForApk)
            throws IOException {

        File tmpZipFile = new File(instantRunSupportDir, "classes.zip");
        Files.createParentDirs(tmpZipFile);
        ZipOutputStream zipFile = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmpZipFile)));
        // no need to compress a zip, the APK itself gets compressed.
        zipFile.setLevel(0);

        try {
            for (File dexFolder : dexFolders) {
                if (dexFolder.getName().contains(INSTANT_RUN_PACKAGES_PREFIX)) {
                    dexFoldersForApk.add(dexFolder);
                } else {
                    for (File file : Files.fileTreeTraverser().breadthFirstTraversal(dexFolder)) {
                        if (file.isFile() && file.getName().endsWith(SdkConstants.DOT_DEX)) {
                            // There are several pieces of code in the runtime library which depends on
                            // this exact pattern, so it should not be changed without thorough testing
                            // (it's basically part of the contract).
                            String entryName = file.getParentFile().getName() + "-" + file.getName();
                            zipFile.putNextEntry(new ZipEntry(entryName));
                            try {
                                Files.copy(file, zipFile);
                            } finally {
                                zipFile.closeEntry();
                            }
                        }

                    }
                }
            }
        } finally {
            zipFile.close();
        }

        // now package that zip file as a zip since this is what the packager is expecting !
        File finalResourceFile = new File(instantRunSupportDir, "resources.zip");
        zipFile = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(finalResourceFile)));
        try {
            zipFile.putNextEntry(new ZipEntry("instant-run.zip"));
            try {
                Files.copy(tmpZipFile, zipFile);
            } finally {
                zipFile.closeEntry();
            }
        } finally {
            zipFile.close();
        }

        return finalResourceFile;
    }

    // ----- ConfigAction -----

    public static class ConfigAction extends PackageAndroidArtifact.ConfigAction<PackageApplication> {

        public ConfigAction(
                @NonNull VariantOutputScope scope,
                @NonNull DexPackagingPolicy dexPackagingPolicy,
                boolean instantRunEnabled) {
            super(scope, dexPackagingPolicy, instantRunEnabled);
        }

        @NonNull
        @Override
        public String getName() {
            return scope.getTaskName("package");
        }

        @NonNull
        @Override
        public Class<PackageApplication> getType() {
            return PackageApplication.class;
        }

        @Override
        public void execute(@NonNull final PackageApplication packageApplication) {
            final VariantScope variantScope = scope.getVariantScope();

            ConventionMappingHelper.map(packageApplication, "outputFile", scope::getPackageApk);

            packageApplication.markerFile =
                    PrePackageApplication.ConfigAction.getMarkerFile(variantScope);
            packageApplication.inOldMode = AndroidGradleOptions.useOldPackaging(
                    variantScope.getGlobalScope().getProject());
            super.execute(packageApplication);
        }
    }

}