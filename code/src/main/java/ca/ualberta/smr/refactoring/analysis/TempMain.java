package ca.ualberta.smr.refactoring.analysis;

import ca.ualberta.smr.refactoring.analysis.database.*;
import ca.ualberta.smr.refactoring.analysis.utils.GitUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.javalite.activejdbc.Base;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ca.ualberta.smr.refactoring.analysis.utils.Utils.log;

public class TempMain {

    public final static String PROJECTS_DIRECTORY = "../projects";
    public final static String PROJECTS_LIST_FILE = "../reposList.txt";

    public static void main(String[] args) throws Exception {
        Base.open();
        new TempMain().run();
        Base.close();
    }


    private void run() throws Exception {
        List<String> projectURLs = Files.readAllLines(Paths.get(PROJECTS_LIST_FILE));
        projectURLs.forEach(this::cloneProject);
        for (String projectURL : projectURLs) {
            if (Project.where("url = ?", projectURL).size() == 0)
                new Project(projectURL, projectURL.substring(projectURL.lastIndexOf('/') + 1)).save();
        }
        Project.findAll().forEach(model -> analyzeProject((Project) model));
//        Project.where("name = 'infinispan'").forEach(model -> analyzeProject((Project) model));
    }

    private void cloneProject(String url) {
        try {
            String projectName = url.substring(url.lastIndexOf('/') + 1);
            Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(new File(PROJECTS_DIRECTORY, projectName))
                    .call();
        } catch (JGitInternalException e){
            System.err.println(e.getMessage());
        } catch (GitAPIException e) {
            e.printStackTrace();
        }
    }

    private void analyzeProject(Project project) {
        try {
            log(String.format("Analyzing %s...", project.getName()));
            GitUtils gitUtils = new GitUtils(new File(PROJECTS_DIRECTORY, project.getName()));
            int mergeCommitIndex = 0;
            for (RevCommit mergeCommit : gitUtils.getMergeCommits()) {
                mergeCommitIndex++;
                log(String.format("Analyzing commit %.7s... (%d/?)", mergeCommit.getName(), mergeCommitIndex));

                // Skip this commit if it already exists in the database.
                if (MergeCommit.where("commit_hash = ?", mergeCommit.getName()).size() > 0) continue;

                Map<String, String> conflictingJavaFiles = new HashMap<>();
                try {
                    boolean isConflicting = gitUtils.isConflicting(mergeCommit, conflictingJavaFiles);
                    // TODO: Move all save operations to the end of the for loop.
                    MergeCommit mergeCommitModel = new MergeCommit(mergeCommit.getName(), project.getID(),
                            isConflicting);
                    if (!mergeCommitModel.saveIt()) continue;
                    MergeParent leftParent = new MergeParent(mergeCommit.getName(), mergeCommit.getParent(0).getName());
                    MergeParent rightParent = new MergeParent(mergeCommit.getName(), mergeCommit.getParent(1).getName());
                    leftParent.saveIt();
                    rightParent.saveIt();

                    for (String path : conflictingJavaFiles.keySet()) {
                        String type = conflictingJavaFiles.get(path);
                        ConflictingFile conflictingFile = new ConflictingFile(mergeCommit.getName(), path, type);
                        conflictingFile.saveIt();
                        // TODO: Maybe remove this condition later?
                        if (type.equalsIgnoreCase("content") ||
                                type.equalsIgnoreCase("add/add")) {
                            List<int[][]> conflictingRegions = gitUtils.getConflictingRegions(path);
                            for (int[][] conflictingLines : conflictingRegions) {
                                ConflictingRegion leftConflictingRegion = new ConflictingRegion(conflictingLines[0][0],
                                        conflictingLines[0][1], mergeCommit.getName(),
                                        mergeCommit.getParent(0).getName(), conflictingFile.getID());
                                ConflictingRegion rightConflictingRegion = new ConflictingRegion(conflictingLines[1][0],
                                        conflictingLines[1][1], mergeCommit.getName(),mergeCommit.getParent(1).getName(),
                                        conflictingFile.getID());
                                leftConflictingRegion.saveIt();
                                rightConflictingRegion.saveIt();

                                Map<String, int[]> leftConflictingRegionHistory = gitUtils.getConflictingRegionHistory(
                                        mergeCommit.getParent(0).getName(), mergeCommit.getParent(1).getName(),
                                        path, conflictingLines[0]);
                                Map<String, int[]> rightConflictingRegionHistory = gitUtils.getConflictingRegionHistory(
                                        mergeCommit.getParent(1).getName(), mergeCommit.getParent(0).getName(),
                                        path, conflictingLines[1]);

                                leftConflictingRegionHistory.forEach((commitHash, region) -> new ConflictingRegionHistory(commitHash, region[0], region[1], region[2], region[3], leftConflictingRegion.getID()).saveIt());
                                rightConflictingRegionHistory.forEach((commitHash, region) -> new ConflictingRegionHistory(commitHash, region[0], region[1], region[2], region[3], rightConflictingRegion.getID()).saveIt());
                            }
                        }
                    }



                } catch (GitAPIException e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException | GitAPIException e) {
            e.printStackTrace();
        }
    }
}
