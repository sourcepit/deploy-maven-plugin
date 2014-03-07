/**
 * Copyright (c) 2014 Sourcepit.org contributors and others. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.sourcepit.tools.mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.deployer.ArtifactDeploymentException;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;

@Mojo( name = "deploy", defaultPhase = LifecyclePhase.DEPLOY, threadSafe = true )
public class DeployCopiedArtifactMojo extends AbstractDeployMojo
{
   private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.+)::(.+)");

   /**
    * When building with multiple threads, reaching the last project doesn't have to mean that all projects are ready to
    * be deployed
    */
   private static final AtomicInteger readyProjectsCounter = new AtomicInteger();

   private static final List<DeployRequest> deployRequests = Collections
      .synchronizedList(new ArrayList<DeployRequest>());

   /**
    */
   @Component
   private MavenProject project;

   @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
   private List<MavenProject> reactorProjects;

   /**
    * Whether every project should be deployed during its own deploy-phase or at the end of the multimodule build. If
    * set to {@code true} and the build fails, none of the reactor projects is deployed
    * 
    * @since 2.8
    */
   @Parameter(defaultValue = "false", property = "deployAtEnd")
   private boolean deployAtEnd;

   /**
    * @deprecated either use project.getArtifact() or reactorProjects.get(i).getArtifact()
    */
   @Parameter(defaultValue = "${project.artifact}", required = true, readonly = true)
   private Artifact artifact;

   /**
    * @deprecated either use project.getPackaging() or reactorProjects.get(i).getPackaging()
    */
   @Parameter(defaultValue = "${project.packaging}", required = true, readonly = true)
   private String packaging;

   /**
    * @deprecated either use project.getFile() or reactorProjects.get(i).getFile()
    */
   @Parameter(defaultValue = "${project.file}", required = true, readonly = true)
   private File pomFile;

   /**
    * Specifies an alternative repository to which the project artifacts should be deployed ( other than those
    * specified in &lt;distributionManagement&gt; ). <br/>
    * Format: id::layout::url
    * <dl>
    * <dt>id</dt>
    * <dd>The id can be used to pick up the correct credentials from the settings.xml</dd>
    * <dt>layout</dt>
    * <dd>Either <code>default</code> for the Maven2 layout or <code>legacy</code> for the Maven1 layout. Maven3 also
    * uses the <code>default</code> layout.</dd>
    * <dt>url</dt>
    * <dd>The location of the repository</dd>
    * </dl>
    */
   @Parameter(property = "altDeploymentRepository")
   private String altDeploymentRepository;

   /**
    * The alternative repository to use when the project has a snapshot version.
    * 
    * @since 2.8
    * @see DeployMojo#altDeploymentRepository
    */
   @Parameter(property = "altSnapshotDeploymentRepository")
   private String altSnapshotDeploymentRepository;

   /**
    * The alternative repository to use when the project has a final version.
    * 
    * @since 2.8
    * @see DeployMojo#altDeploymentRepository
    */
   @Parameter(property = "altReleaseDeploymentRepository")
   private String altReleaseDeploymentRepository;

   /**
    * @deprecated either use project.getAttachedArtifacts() or reactorProjects.get(i).getAttachedArtifacts()
    */
   @Parameter(defaultValue = "${project.attachedArtifacts}", required = true, readonly = true)
   private List attachedArtifacts;

   /**
    * Set this to 'true' to bypass artifact deploy
    * 
    * @since 2.4
    */
   @Parameter(property = "maven.deploy.skip", defaultValue = "false")
   private boolean skip;

   public void execute() throws MojoExecutionException, MojoFailureException
   {
      boolean projectsReady = readyProjectsCounter.incrementAndGet() == reactorProjects.size();

      if (skip)
      {
         getLog().info("Skipping artifact deployment");
      }
      else
      {
         failIfOffline();

         DeployRequest currentExecutionDeployRequest = new DeployRequest().setProject(project)
            .setUpdateReleaseInfo(isUpdateReleaseInfo()).setRetryFailedDeploymentCount(getRetryFailedDeploymentCount())
            .setAltReleaseDeploymentRepository(altReleaseDeploymentRepository)
            .setAltSnapshotDeploymentRepository(altSnapshotDeploymentRepository)
            .setAltDeploymentRepository(altDeploymentRepository);

         if (!deployAtEnd)
         {
            deployProject(currentExecutionDeployRequest);
         }
         else
         {
            deployRequests.add(currentExecutionDeployRequest);
            if (!projectsReady)
            {
               getLog().info(
                  "Deploying " + project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion()
                     + " at end");
            }
         }
      }

      if (projectsReady)
      {
         synchronized (deployRequests)
         {
            while (!deployRequests.isEmpty())
            {
               deployProject(deployRequests.remove(0));
            }
         }
      }
   }

   private void deployProject(DeployRequest request) throws MojoExecutionException, MojoFailureException
   {
      Artifact artifact = ArtifactUtils.copyArtifact(request.getProject().getArtifact());
      
      String packaging = request.getProject().getPackaging();
      File pomFile = request.getProject().getFile();

      @SuppressWarnings("unchecked")
      List<Artifact> attachedArtifacts = new ArrayList<Artifact>();
      ArtifactUtils.copyArtifacts(request.getProject().getAttachedArtifacts(), attachedArtifacts);

      ArtifactRepository repo = getDeploymentRepository(request.getProject(), request.getAltDeploymentRepository(),
         request.getAltReleaseDeploymentRepository(), request.getAltSnapshotDeploymentRepository());

      String protocol = repo.getProtocol();

      if (protocol.equalsIgnoreCase("scp"))
      {
         File sshFile = new File(System.getProperty("user.home"), ".ssh");

         if (!sshFile.exists())
         {
            sshFile.mkdirs();
         }
      }

      // Deploy the POM
      boolean isPomArtifact = "pom".equals(packaging);
      if (!isPomArtifact)
      {
         ArtifactMetadata metadata = new ProjectArtifactMetadata(artifact, pomFile);
         artifact.addMetadata(metadata);
      }

      if (request.isUpdateReleaseInfo())
      {
         artifact.setRelease(true);
      }

      int retryFailedDeploymentCount = request.getRetryFailedDeploymentCount();

      try
      {
         if (isPomArtifact)
         {
            deploy(pomFile, artifact, repo, getLocalRepository(), retryFailedDeploymentCount);
         }
         else
         {
            File file = artifact.getFile();

            if (file != null && file.isFile())
            {
               deploy(file, artifact, repo, getLocalRepository(), retryFailedDeploymentCount);
            }
            else if (!attachedArtifacts.isEmpty())
            {
               getLog().info("No primary artifact to deploy, deploying attached artifacts instead.");

               Artifact pomArtifact = artifactFactory.createProjectArtifact(artifact.getGroupId(),
                  artifact.getArtifactId(), artifact.getBaseVersion());
               pomArtifact.setFile(pomFile);
               if (request.isUpdateReleaseInfo())
               {
                  pomArtifact.setRelease(true);
               }

               deploy(pomFile, pomArtifact, repo, getLocalRepository(), retryFailedDeploymentCount);

               // propagate the timestamped version to the main artifact for the attached artifacts to pick it up
               artifact.setResolvedVersion(pomArtifact.getVersion());
            }
            else
            {
               String message = "The packaging for this project did not assign a file to the build artifact";
               throw new MojoExecutionException(message);
            }
         }

         for (Artifact attached : attachedArtifacts)
         {
            deploy(attached.getFile(), attached, repo, getLocalRepository(), retryFailedDeploymentCount);
         }
      }
      catch (ArtifactDeploymentException e)
      {
         throw new MojoExecutionException(e.getMessage(), e);
      }
   }

   ArtifactRepository getDeploymentRepository(MavenProject project, String altDeploymentRepository,
      String altReleaseDeploymentRepository, String altSnapshotDeploymentRepository) throws MojoExecutionException,
      MojoFailureException
   {
      ArtifactRepository repo = null;

      String altDeploymentRepo;
      if (ArtifactUtils.isSnapshot(project.getVersion()) && altSnapshotDeploymentRepository != null)
      {
         altDeploymentRepo = altSnapshotDeploymentRepository;
      }
      else if (!ArtifactUtils.isSnapshot(project.getVersion()) && altReleaseDeploymentRepository != null)
      {
         altDeploymentRepo = altReleaseDeploymentRepository;
      }
      else
      {
         altDeploymentRepo = altDeploymentRepository;
      }

      if (altDeploymentRepo != null)
      {
         getLog().info("Using alternate deployment repository " + altDeploymentRepo);

         Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(altDeploymentRepo);

         if (!matcher.matches())
         {
            throw new MojoFailureException(altDeploymentRepo, "Invalid syntax for repository.",
               "Invalid syntax for alternative repository. Use \"id::layout::url\".");
         }
         else
         {
            String id = matcher.group(1).trim();
            String layout = matcher.group(2).trim();
            String url = matcher.group(3).trim();

            ArtifactRepositoryLayout repoLayout = getLayout(layout);

            repo = repositoryFactory.createDeploymentArtifactRepository(id, url, repoLayout, true);
         }
      }

      if (repo == null)
      {
         repo = project.getDistributionManagementArtifactRepository();
      }

      if (repo == null)
      {
         String msg = "Deployment failed: repository element was not specified in the POM inside"
            + " distributionManagement element or in -DaltDeploymentRepository=id::layout::url parameter";

         throw new MojoExecutionException(msg);
      }

      return repo;
   }
}
