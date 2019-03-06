/* Copyright (c) 2018 - 2018 TomTom N.V. All rights reserved.
 *
 * This software is the proprietary copyright of TomTom N.V. and its subsidiaries and may be
 * used for internal evaluation purposes or commercial use strictly subject to separate
 * licensee agreement between you and TomTom. If you are the licensee, you are only permitted
 * to use this Software in accordance with the terms of your license agreement. If you are
 * not the licensee then you are not authorised to use this software in any manner and should
 * immediately return it to TomTom N.V.
 */

def call(Map pipelineParams) {
  def LOG_TAG = "[pythonPipeline]"

  if (!pipelineParams.pypiCredentials) {
    error("${LOG_TAG} Please provide pipelineParams.pypiCredentials")
  }
  if (!pipelineParams.sshAgentUser) {
    error("${LOG_TAG} Please provide pipelineParams.sshAgentUser")
  }
  if (!pipelineParams.changesOnlyInFiles) {
    pipelineParams["changesOnlyInFiles"] = ["CHANGELOG", "README", ".gitignore"]
    echo("${LOG_TAG} Using default value for changesOnlyInFiles: '${pipelineParams.changesOnlyInFiles}'")
  }
  if (!pipelineParams.commitsOnlyWithMessages) {
    pipelineParams["commitsOnlyWithMessages"] = ["Bump version"]
    echo("${LOG_TAG} Using default value for commitsOnlyWithMessages: '${pipelineParams.commitsOnlyWithMessages}'")
  }
  if (!pipelineParams.pythonBuilderImage) {
    error("${LOG_TAG} Please provide pipelineParams.pythonBuilderImage")
  }
  if (!pipelineParams.pythonBuilderArgs) {
    pipelineParams["pythonBuilderArgs"] = "-v /etc/passwd:/etc/passwd:ro -v /opt/jenkins/.ssh:/opt/jenkins/.ssh:ro --network host" 
  }

  pipeline {
    agent {
      docker {
        image pipelineParams.pythonBuilderImage
        args pipelineParams.pythonBuilderArgs 
        reuseNode true
      }
    }

    parameters {
        booleanParam(defaultValue: false, description: 'Enable to force a release build', name: 'forceRelease')
    }

    options {
      disableConcurrentBuilds()
    }

    stages {
      stage("Bump version Release") {
        when {
          beforeAgent true
          allOf {
            branch "master"
            not {
              expression {
                forceRelease || (commits.onlyWith(pipelineParams.commitsOnlyWithMessages as String[]) || changes.onlyIn(pipelineParams.changesOnlyInFiles as String[]))
              }
            }
          }
        }
        steps {
          sh "bumpversion release"
        }
      }

      stage("Build") {
        steps {
          sh "python setup.py build"
        }
      }

      stage("Execute Pylint") {
        steps {
          // Necessary to avoid issues with creating pylint files that track the delta of warnings.
          withEnv(["HOME=$WORKSPACE"]) {
            // setuptools-lint does not make a good job in installing all its dependencies.
            sh "python setup.py install --user"
            sh "python setup.py lint --lint-output-format parseable"
          }
        }
      }

      stage("Execute Tests") {
        steps {
          sh "python setup.py test --addopts '--cov-report xml:build/coverage.xml --cov-report term --cov-branch --junitxml=build/test_results.xml'"
        }
      }

      stage("Build Docs") {
        steps {
          // Necessary to be able to locally install packages, which is required for build_sphinx
          withEnv(["HOME=$WORKSPACE"]) {
            sh "python setup.py install --user"
            sh "python setup.py build_sphinx"
          }
        }
      }

      stage("Deploy") {
        when {
          beforeAgent true
          allOf {
            branch "master"
            not {
              expression {
                forceRelease || (commits.onlyWith(pipelineParams.commitsOnlyWithMessages as String[]) || changes.onlyIn(pipelineParams.changesOnlyInFiles as String[]))
              }
            }
          }
        }
        steps {
          withCredentials([usernamePassword(credentialsId: pipelineParams.pypiCredentials, usernameVariable: "USERNAME", passwordVariable: "PASSWORD")]) {
            sh "python setup.py sdist"

            sh "twine upload --verbose -u $USERNAME -p $PASSWORD --repository-url https://artifactory.navkit-pipeline.tt3.com/artifactory/api/pypi/pypi-local dist/*"
          }
        }
      }

      // Deploy the html documents to the docs branch, which when using "BitBucket Pages" allows serving the documentation directly
      stage("Deploy Docs") {
        when {
          beforeAgent true
          allOf {
            branch "master"
            not {
              expression {
                forceRelease || (commits.onlyWith(pipelineParams.commitsOnlyWithMessages as String[]) || changes.onlyIn(pipelineParams.changesOnlyInFiles as String[]))
              }
            }
          }
        }
        steps {
          sshagent([pipelineParams.sshAgentUser]) {
            script {
              String version = sh(script: "python setup.py --version", returnStdout: true).trim()
              sh "git checkout docs"
              sh "rm -rf docs"
              sh "mv build/sphinx/html/ docs/"
              sh "git add docs/."
              sh "git commit -m \"Documentation update to $version\""
              sh "git tag docs-$version"
              sh "git push origin docs"
              sh "git push origin docs --tags"
            }
          }
        }
      }

      stage("Bump Version Patch") {
        when {
          beforeAgent true
          allOf {
            branch "master"
            not {
              expression {
                forceRelease || (commits.onlyWith(pipelineParams.commitsOnlyWithMessages as String[]) || changes.onlyIn(pipelineParams.changesOnlyInFiles as String[]))
              }
            }
          }
        }
        steps {
          sshagent([pipelineParams.sshAgentUser]) {
            sh "git checkout master"

            sh "bumpversion --no-tag patch"

            sh "git push origin master --tags"
          }
        }
      }
    }
    post {
      always {
        recordIssues enabledForFailure: true, tool: pyLint(), healthy: 0
        junit 'build/test_results.xml'
        cobertura coberturaReportFile: 'build/coverage.xml'
      }
    }
  }
}