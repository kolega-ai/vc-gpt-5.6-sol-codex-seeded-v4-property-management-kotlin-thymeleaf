pipeline {
  agent any
  tools { jdk 'java21' }
  stages {
    stage('Build and test') { steps { sh './gradlew --no-daemon clean test bootJar' } }
    stage('Reports') { steps { junit allowEmptyResults: false, testResults: 'build/test-results/test/*.xml'; archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true } }
  }
  post { always { cleanWs() } }
}

