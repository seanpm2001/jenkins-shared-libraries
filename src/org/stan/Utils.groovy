package org.stan

import hudson.model.Result
import jenkins.model.CauseOfInterruption.UserInterruption

def killOldBuilds() {
  def hi = Hudson.instance
  def pname = env.JOB_NAME.split('/')[0]

  hi.getItem(pname).getItem(env.JOB_BASE_NAME).getBuilds().each{ build ->
    def exec = build.getExecutor()

    if (build.number != currentBuild.number && exec != null) {
      exec.interrupt(
        Result.ABORTED,
        new CauseOfInterruption.UserInterruption(
          "job #${currentBuild.number} supersedes this build"
        )
      )
      println("Aborted previous running build #${build.number}")
    }
  }
}

def isBranch(env, String b) { env.BRANCH_NAME == b }

def updateUpstream(env, String upstreamRepo) {
    if (isBranch(env, 'develop')) {
        node('osx || linux') {
            retry(3) {
                deleteDir()
                withCredentials([
                    usernamePassword(
                        credentialsId: 'a630aebc-6861-4e69-b497-fd7f496ec46b',
                        usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                    sh "git clone --recursive https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/stan-dev/${upstreamRepo}.git"
                }
                sh """
                cd ${upstreamRepo}
                git config --global user.email "mc.stanislaw@gmail.com"
                git config --global user.name "Stan Jenkins"
                curl -O https://raw.githubusercontent.com/stan-dev/ci-scripts/master/jenkins/create-${upstreamRepo}-pull-request.sh
                bash create-${upstreamRepo}-pull-request.sh
            """
                deleteDir() // don't leave credentials on disk
            }
        }
    }
}

def isBuildAReplay() {
  def replyClassName = "org.jenkinsci.plugins.workflow.cps.replay.ReplayCause"
  currentBuild.rawBuild.getCauses().any{ cause -> cause.toString().contains(replyClassName) }
}

def verifyChanges(String sourceCodePaths) {

    def commitHash = ""
    def changeTarget = ""
    def currentRepository = ""
    def mergeStatus = -1

    if (env.GIT_URL) {
        currentRepository = sh(script: "echo ${env.GIT_URL} | cut -d'/' -f 5", returnStdout: true)
    }
    else{
        currentRepository = sh(script: "echo ${env.CHANGE_URL} | cut -d'/' -f 5", returnStdout: true)
    }

    sh(script:"ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts", returnStdout: false)
    sh(script: "git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*' --replace-all", returnStdout: true)
    sh(script: "git remote rm forkedOrigin || true", returnStdout: true)
    sh(script: "git fetch --all", returnStdout: true)

    if (env.CHANGE_TARGET) {
        println "This build is a PR, checking out target branch to compare changes."
        changeTarget = env.CHANGE_TARGET

        if (env.CHANGE_FORK) {
            println "This PR is a fork."

            sh("""
                git config --global user.email "mc.stanislaw@gmail.com"
                git config --global user.name "Stan Jenkins"
            """)

            withCredentials([usernamePassword(credentialsId: 'a630aebc-6861-4e69-b497-fd7f496ec46b', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
                sh """#!/bin/bash
                   git remote add forkedOrigin https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/${env.CHANGE_FORK}/${currentRepository}
                   git fetch forkedOrigin
                   git pull && git checkout forkedOrigin/${env.CHANGE_BRANCH}
                """
            }

            commitHash = sh(script: "git rev-parse HEAD | tr '\\n' ' '", returnStdout: true)
            sh(script: "git pull && git checkout origin/${changeTarget}", returnStdout: false)
            sh(script: "git checkout forkedOrigin/${env.CHANGE_BRANCH}", returnStdout: false)
        }
        else {
            sh(script: "git pull && git checkout ${env.CHANGE_BRANCH}", returnStdout: false)
            commitHash = sh(script: "git rev-parse HEAD | tr '\\n' ' '", returnStdout: true)
            sh(script: "git pull && git checkout ${changeTarget}", returnStdout: false)
            sh(script: "git checkout ${env.CHANGE_BRANCH}", returnStdout: false)
        }

        println "Trying to merge origin/master into current PR branch"
        mergeStatus = sh(returnStatus: true, script: "git merge --no-commit --no-ff origin/master")
        if (mergeStatus != 0) {
            println "Auto merge has failed, aborting merge."
            sh(script: "git merge --abort", returnStdout: false)
        }
    }
    else{
        println "This build is not PR, checking out current branch and extract HEAD^1 commit to compare changes or develop when downstream_tests."
        if (env.BRANCH_NAME == "downstream_tests" || env.BRANCH_NAME == "downstream_hotfix"){
            // Exception added for Math PR #1832
            if (params.math_pr != null && params.math_pr == "PR-1832"){
                return true
            }
            return false
        }
        else{
            sh(script: "git pull && git checkout ${env.BRANCH_NAME}", returnStdout: false)
            commitHash = sh(script: "git rev-parse HEAD | tr '\\n' ' '", returnStdout: true)
            changeTarget = sh(script: "git rev-parse HEAD^1 | tr '\\n' ' '", returnStdout: true)
        }
    }

    def differences = ""
    if (env.CHANGE_FORK) {
        println "Comparing differences between current ${commitHash} from forked repository ${env.CHANGE_FORK}/${currentRepository} and target ${changeTarget}"
        if (mergeStatus != 0){
            differences = sh(script: """
                for i in ${sourceCodePaths};
                do
                    git diff forkedOrigin/${env.CHANGE_BRANCH} origin/${changeTarget} -- \$i
                done
            """, returnStdout: true)
        }
        else{
            differences = sh(script: """
                for i in ${sourceCodePaths};
                do
                    git diff --staged origin/${changeTarget} -- \$i
                done
            """, returnStdout: true)
        }
    }
    else{
        println "Comparing differences between current ${commitHash} and target ${changeTarget}"
        if (mergeStatus != 0){
            differences = sh(script: """
                for i in ${sourceCodePaths};
                do
                    git diff ${commitHash} ${changeTarget} -- \$i
                done
            """, returnStdout: true)
        }
        else {
            differences = sh(script: """
                for i in ${sourceCodePaths};
                do
                    git diff --staged ${changeTarget} -- \$i
                done
            """, returnStdout: true)
        }
    }

    println differences

    // Remove origin
    sh(script: "git remote rm forkedOrigin || true", returnStdout: true)
    //Hard reset to change branch
    sh(script: "git merge --abort || true", returnStdout: true)
    sh(script: "git reset --hard ${commitHash}", returnStdout: true)

    if (differences?.trim()) {
        println "There are differences in the source code, CI/CD will run."
        return false
    }
    else if (isBuildAReplay()){
        println "Build is a replay."
        return false
    }
    else{
        println "There are no differences in the source code, CI/CD will not run."
        return true
    }
}

def checkout_pr(String repo, String dir, String pr) {
    if (pr == '') {
        if (env.BRANCH_NAME == 'master'){
            pr = "master"
        }
        else {
            pr = "develop"
        }
    }
    prNumber = pr.tokenize('-').last()
    if (pr.startsWith("PR-")) {
        sh """
          cd ${dir}
          git fetch https://github.com/stan-dev/${repo} +refs/pull/${prNumber}/merge:refs/remotes/origin/pr/${prNumber}/merge
          git checkout refs/remotes/origin/pr/${prNumber}/merge
        """
    } else {
        sh "cd ${dir} && git checkout ${pr} && git pull origin ${pr}"

    }
    sh "cd ${dir} && git clean -xffd"
}

def mailBuildResults(String _ = "", additionalEmails='') {
    script {
        if (env.BRANCH_NAME == 'downstream_tests') return
        try {
            emailext (
                subject: "[StanJenkins] ${currentBuild.currentResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'",
                body: """${currentBuild.currentResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.CHANGE_TITLE} ${env.BRANCH_NAME}): Check console output at ${env.BUILD_URL}
Or, check out the new blue ocean view (easier for most errors) at ${env.RUN_DISPLAY_URL}
""",
                recipientProviders: [brokenBuildSuspects(), requestor(), culprits()],
                to: additionalEmails
            )
        } catch (all) {
            println "Encountered the following exception sending email; please ignore:"
            println all
            println "End ignoreable email-sending exception."
        }
    }
}
