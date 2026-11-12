pipeline {
    agent any

    options {
        // Keep only the last 3 builds (and their artifacts)
        buildDiscarder(logRotator(numToKeepStr: '3', artifactNumToKeepStr: '3'))

        // Abort the whole pipeline if it runs longer than 1 hour
        timeout(time: 1, unit: 'HOURS')

        // Add timestamps to every console log line (Timestamper plugin)
        timestamps()

        // Don't allow two builds of this job to run at the same time
        disableConcurrentBuilds()
    }

    environment {
        DOCKERHUB_IMAGE = 'tennis-react-app'
        DOCKERHUB_USER = 'mrtol'
        TAG = "1.0.${env.BUILD_NUMBER}"
        FULL_IMAGE = "${DOCKERHUB_IMAGE}:${TAG}"
    }

    stages {
        stage('Checkout') {
            steps {
                git branch: 'master', url: 'https://github.com/tol-san/react-app.git'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                script {
                    def scannerHome = tool 'SonarScanner'
                    withSonarQubeEnv('SONARQUBE') {
                        // SONAR_HOST_URL and SONAR_AUTH_TOKEN are injected by withSonarQubeEnv
                        sh """
                        ${scannerHome}/bin/sonar-scanner \
                          -Dsonar.projectKey=tennis-react-app \
                          -Dsonar.projectName=tennis-react-app \
                          -Dsonar.sources=. \
                          -Dsonar.exclusions=node_modules/**,build/**
                        """
                    }
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    // Fails the pipeline if the gate is not passed
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Dependency Check') {
            steps {
                dependencyCheck additionalArguments: '''
                    --scan .
                    --format HTML
                    --format XML
                    --data /var/lib/jenkins/dc-data
                    --disableYarnAudit
                ''',
                odcInstallation: 'OWASP-Dependency-Check',
                nvdCredentialsId: 'nvd-api-key'

                dependencyCheckPublisher pattern: '**/dependency-check-report.xml',
                                         failedTotalCritical: 1,
                                         failedTotalHigh: 5,
                                         unstableTotalMedium: 10
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                docker build -t ${DOCKERHUB_IMAGE}:${TAG} -t ${DOCKERHUB_IMAGE}:latest .
                '''
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
                    docker tag ${DOCKERHUB_IMAGE}:${TAG} ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:${TAG}
                    docker tag ${DOCKERHUB_IMAGE}:latest ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:latest

                    docker push ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:${TAG}
                    docker push ${DOCKERHUB_USER}/${DOCKERHUB_IMAGE}:latest
                    '''
                }
            }
        }

        stage('Choose Environment') {
            steps {
                script {
                    def userInput = timeout(time: 15, unit: 'MINUTES') {
                        input message: 'Choose Server', parameters: [
                            choice(choices: ['prod-server', 'stage-server'], name: 'CHOOSE YOUR ENVIRONMENT:')
                        ]
                    }

                    node(userInput) {
                        stage('Deploy Container') {
                            sh """
                            echo "Chosen environment is: ${userInput}"
                            docker rm -f tennis-react-container || true
                            docker run -dp 3000:80 --name tennis-react-container ${DOCKERHUB_USER}/${FULL_IMAGE}
                            """
                        }
                    }
                }
            }
        }
    }
}