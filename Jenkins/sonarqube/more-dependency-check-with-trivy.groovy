pipeline {
    // No global agent: the approval wait must NOT hold an executor.
    // Checkout/scan/build/push stages share ONE node via the parent 'CI' stage.
    agent none

    options {
        buildDiscarder(logRotator(numToKeepStr: '3', artifactNumToKeepStr: '3'))
        timeout(time: 1, unit: 'HOURS')
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
    }

    environment {
        DOCKERHUB_IMAGE = 'tennis-react-app'
        // Must match the username stored in the 'DOCKERHUB_ID' Jenkins credential
        DOCKERHUB_USER  = 'mrtol'
        TAG             = "1.0.${env.BUILD_NUMBER}"
        FULL_IMAGE      = "${DOCKERHUB_IMAGE}:${TAG}"
        REMOTE_IMAGE    = "${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:${TAG}"

        // prod.Dockerfile = nginx image (repo's nginx.conf listens on port 80)
        DOCKERFILE      = 'prod.Dockerfile'
        CONTAINER_PORT  = '80'      // fallback only; deploy auto-detects the image's EXPOSEd port
        HOST_PORT       = '3000'
        CONTAINER_NAME  = 'tennis-react-container'
    }

    stages {

        // ============================ CI (one node) ============================
        stage('CI') {
            agent any

            stages {

                stage('Checkout') {
                    steps {
                        git branch: 'master',
                            url: 'https://github.com/keoKAY/reactjs-devop8-template.git',
                            changelog: false,
                            poll: false
                    }
                }

                stage('Preflight') {
                    steps {
                        // Fail fast if the files the build needs are missing
                        sh '''
                        test -f "${DOCKERFILE}" || { echo "Missing ${DOCKERFILE}"; exit 1; }
                        test -f nginx.conf      || { echo "Missing nginx.conf"; exit 1; }
                        echo "Using Dockerfile: ${DOCKERFILE}"
                        '''
                    }
                }

                stage('SonarQube Analysis') {
                    steps {
                        script {
                            def scannerHome = tool 'SonarScanner'
                            withSonarQubeEnv('SONARQUBE') {
                                sh """
                                ${scannerHome}/bin/sonar-scanner \
                                  -Dsonar.projectKey=reactjs-devop8-template \
                                  -Dsonar.projectName=reactjs-devop8-template \
                                  -Dsonar.sources=. \
                                  -Dsonar.sourceEncoding=UTF-8 \
                                  -Dsonar.exclusions=node_modules/**,build/**,public/**,tempCodeRunnerFile.js
                                """
                            }
                        }
                    }
                }

                stage('Quality Gate') {
                    steps {
                        // Requires a SonarQube webhook -> <jenkins-url>/sonarqube-webhook/
                        script {
                            env.QG_PASSED = 'false'
                            def qg = null
                            timeout(time: 5, unit: 'MINUTES') {
                                // No abortPipeline here: we check the status ourselves
                                qg = waitForQualityGate()
                            }
                            echo "SonarQube Quality Gate status: ${qg.status}"
                            if (qg.status != 'OK') {
                                error "Quality Gate FAILED (${qg.status}) - stopping pipeline. No image will be built."
                            }
                            env.QG_PASSED = 'true'
                        }
                    }
                }

                stage('Dependency Check') {
                    steps {
                        dependencyCheck additionalArguments: '''
                            --scan .
                            --format HTML
                            --format XML
                            --format JSON
                            --data /var/lib/jenkins/dc-data
                            --disableYarnAudit
                        ''',
                        odcInstallation: 'OWASP-Dependency-Check',
                        nvdCredentialsId: 'nvd-api-key'

                        // Verify threshold semantics for your plugin version
                        dependencyCheckPublisher pattern: '**/dependency-check-report.xml',
                                                 failedTotalCritical: 1,
                                                 failedTotalHigh: 5,
                                                 unstableTotalMedium: 10
                    }
                    post {
                        always {
                            // Shows up as "Dependency-Check Warnings" on the build page
                            recordIssues enabledForFailure: true,
                                         tool: owaspDependencyCheck(pattern: '**/dependency-check-report.json'),
                                         id: 'dependency-check',
                                         name: 'Dependency-Check'
                        }
                    }
                }

                stage('Build Image') {
                    steps {
                        script {
                            // Defense in depth: never build unless the gate explicitly passed
                            if (env.QG_PASSED != 'true') {
                                error 'Quality Gate did not pass - refusing to build image.'
                            }
                        }
                        sh '''
                        docker build --pull -f "${DOCKERFILE}" \
                            -t ${DOCKERHUB_IMAGE}:${TAG} \
                            -t ${DOCKERHUB_IMAGE}:latest .
                        '''
                    }
                }

                stage('Trivy Scan') {
                    steps {
                        sh '''
                        # 1) Full report (HIGH + CRITICAL) - never fails the build
                        trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
                            --no-progress --format table --output trivy-report.txt \
                            ${DOCKERHUB_IMAGE}:${TAG}

                        # 1b) Same findings as JSON - this is what Warnings NG reads
                        trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
                            --no-progress --format json --output trivy-report.json \
                            ${DOCKERHUB_IMAGE}:${TAG}

                        # 2) Gate: fail the build if any fixable CRITICAL vulnerability exists
                        trivy image --scanners vuln --severity CRITICAL --ignore-unfixed \
                            --exit-code 1 --no-progress \
                            ${DOCKERHUB_IMAGE}:${TAG}
                        '''
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'trivy-report.txt,trivy-report.json', allowEmptyArchive: true

                            // Shows up as "Trivy Warnings" on the build page; runs even if the gate failed
                            recordIssues enabledForFailure: true,
                                         tool: trivy(pattern: 'trivy-report.json'),
                                         id: 'trivy',
                                         name: 'Trivy'
                        }
                    }
                }

                stage('Push Image') {
                    steps {
                        withCredentials([usernamePassword(
                            credentialsId: 'DOCKERHUB_ID',
                            passwordVariable: 'DOCKERHUB_PASS',
                            usernameVariable: 'DOCKERHUB_USERNAME'
                        )]) {
                            sh '''
                            echo "$DOCKERHUB_PASS" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin

                            docker tag ${DOCKERHUB_IMAGE}:${TAG}    ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:${TAG}
                            docker tag ${DOCKERHUB_IMAGE}:latest    ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:latest

                            docker push ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:${TAG}
                            docker push ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:latest
                            '''
                        }
                    }
                    post {
                        always {
                            sh 'docker logout || true'
                        }
                    }
                }
            }

            post {
                always {
                    // Remove dangling layers so the agent disk doesn't fill up
                    sh 'docker image prune -f || true'
                }
            }
        }

        // ================== Approval (no executor is held) ==================
        stage('Choose Environment') {
            agent none
            steps {
                script {
                    def selected = timeout(time: 15, unit: 'MINUTES') {
                        input message: 'Choose deployment server',
                              ok: 'Deploy',
                              // submitter: 'admin,devops-team',   // restrict who can approve
                              parameters: [
                                  choice(name: 'ENVIRONMENT',
                                         choices: ['stage-server', 'prod-server'],
                                         description: 'Must match an agent label in Jenkins')
                              ]
                    }
                    env.TARGET_SERVER = selected
                    echo "Chosen environment: ${env.TARGET_SERVER}"
                }
            }
        }

        // ======================= Deploy on chosen node =======================
        stage('Deploy Container') {
            agent none
            steps {
                script {
                    node(env.TARGET_SERVER) {
                        sh '''
                        echo "Deploying ${REMOTE_IMAGE} to $NODE_NAME"

                        docker pull ${REMOTE_IMAGE}

                        # Use the port the image actually EXPOSEs; fall back to CONTAINER_PORT
                        EXPOSED=$(docker inspect --format '{{range $p, $_ := .Config.ExposedPorts}}{{$p}} {{end}}' ${REMOTE_IMAGE} | awk '{print $1}' | cut -d/ -f1)
                        PORT_IN=${EXPOSED:-${CONTAINER_PORT}}
                        echo "Mapping host ${HOST_PORT} -> container ${PORT_IN}"

                        docker rm -f ${CONTAINER_NAME} || true
                        docker run -d --restart unless-stopped \
                            -p ${HOST_PORT}:${PORT_IN} \
                            --name ${CONTAINER_NAME} \
                            ${REMOTE_IMAGE}

                        # Basic post-deploy check: container must still be running
                        sleep 5
                        if [ -z "$(docker ps -q --filter name=${CONTAINER_NAME} --filter status=running)" ]; then
                            echo "Container is not running. Last logs:"
                            docker logs --tail 50 ${CONTAINER_NAME} || true
                            exit 1
                        fi
                        docker logs --tail 10 ${CONTAINER_NAME}

                        docker image prune -f || true
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline OK: ${env.REMOTE_IMAGE} deployed to ${env.TARGET_SERVER}"
        }
        failure {
            echo 'Pipeline FAILED - check the failing stage log and trivy-report.txt'
        }
    }
}