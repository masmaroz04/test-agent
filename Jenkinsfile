pipeline {
    agent { label 'linux && docker' }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(name: 'ACR_NAME', defaultValue: 'usersapiacr1', description: 'Azure Container Registry name; full login server is also accepted')
        string(name: 'ACR_LOGIN_SERVER', defaultValue: 'usersapiacr1.azurecr.io', description: 'ACR login server')
        string(name: 'IMAGE_REPO', defaultValue: 'users-api', description: 'Repository name in ACR')
        string(name: 'AKS_RESOURCE_GROUP', defaultValue: 'rg-users-api', description: 'Resource group containing AKS')
        string(name: 'AKS_CLUSTER_NAME', defaultValue: 'aks-users-api', description: 'AKS cluster name')
        string(name: 'K8S_NAMESPACE', defaultValue: 'users-api', description: 'Kubernetes namespace')
        booleanParam(name: 'ENABLE_INGRESS_TLS', defaultValue: false, description: 'Enable public HTTPS endpoint via Ingress + cert-manager')
        booleanParam(name: 'INSTALL_INGRESS_STACK', defaultValue: false, description: 'Install/update ingress-nginx and cert-manager')
        string(name: 'INGRESS_HOST', defaultValue: '', description: 'Public DNS host for this API')
        string(name: 'INGRESS_CLASS', defaultValue: 'nginx', description: 'Ingress class name')
        string(name: 'TLS_SECRET_NAME', defaultValue: 'users-api-tls', description: 'TLS secret name in app namespace')
        string(name: 'CLUSTER_ISSUER_NAME', defaultValue: 'letsencrypt-prod', description: 'cert-manager ClusterIssuer name')
        string(name: 'LETSENCRYPT_EMAIL', defaultValue: '', description: 'Email used by ACME account')
        string(name: 'ACME_SERVER', defaultValue: 'https://acme-v02.api.letsencrypt.org/directory', description: 'ACME server URL')
        string(name: 'INGRESS_NGINX_MANIFEST_URL', defaultValue: 'https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml', description: 'ingress-nginx install manifest URL')
        string(name: 'CERT_MANAGER_MANIFEST_URL', defaultValue: 'https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml', description: 'cert-manager install manifest URL')
    }

    environment {
        AZURE_SP = credentials('azure-sp')
        AZURE_TENANT_ID = credentials('azure-tenant-id')
        AZURE_SUBSCRIPTION_ID = credentials('azure-subscription-id')
        ACR_NAME_ENV = "${params.ACR_NAME}"
        ACR_LOGIN_SERVER_ENV = "${params.ACR_LOGIN_SERVER}"
        IMAGE_REPO_ENV = "${params.IMAGE_REPO}"
        AKS_RESOURCE_GROUP_ENV = "${params.AKS_RESOURCE_GROUP}"
        AKS_CLUSTER_NAME_ENV = "${params.AKS_CLUSTER_NAME}"
        K8S_NAMESPACE_ENV = "${params.K8S_NAMESPACE}"
        INGRESS_HOST_ENV = "${params.INGRESS_HOST}"
        INGRESS_CLASS_ENV = "${params.INGRESS_CLASS}"
        TLS_SECRET_NAME_ENV = "${params.TLS_SECRET_NAME}"
        CLUSTER_ISSUER_NAME_ENV = "${params.CLUSTER_ISSUER_NAME}"
        LETSENCRYPT_EMAIL_ENV = "${params.LETSENCRYPT_EMAIL}"
        ACME_SERVER_ENV = "${params.ACME_SERVER}"
        INGRESS_NGINX_MANIFEST_URL_ENV = "${params.INGRESS_NGINX_MANIFEST_URL}"
        CERT_MANAGER_MANIFEST_URL_ENV = "${params.CERT_MANAGER_MANIFEST_URL}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw -B clean test'
            }
        }

        stage('Package') {
            steps {
                sh './mvnw -B package -DskipTests'
            }
        }

        stage('Azure Login') {
            steps {
                script {
                    def requiredParams = [
                        'ACR_LOGIN_SERVER': env.ACR_LOGIN_SERVER_ENV,
                        'AKS_RESOURCE_GROUP': env.AKS_RESOURCE_GROUP_ENV,
                        'AKS_CLUSTER_NAME': env.AKS_CLUSTER_NAME_ENV,
                        'K8S_NAMESPACE': env.K8S_NAMESPACE_ENV
                    ]
                    def missingParams = requiredParams.findAll { key, value -> !value?.trim() }.keySet()
                    if (!missingParams.isEmpty()) {
                        error("Missing required Jenkins parameters: ${missingParams.join(', ')}. Re-open 'Build with Parameters' and fill them in.")
                    }
                }
                sh '''
                  az login --service-principal \
                    --username "$AZURE_SP_USR" \
                    --password "$AZURE_SP_PSW" \
                    --tenant "$AZURE_TENANT_ID"
                  az account set --subscription "$AZURE_SUBSCRIPTION_ID"
                '''
            }
        }

        stage('Build and Push Image') {
            steps {
                script {
                    def normalizedAcrName = env.ACR_NAME_ENV.trim().toLowerCase()
                    if (normalizedAcrName.endsWith('.azurecr.io')) {
                        normalizedAcrName = normalizedAcrName.replaceFirst(/\.azurecr\.io$/, '')
                    }
                    if (!(normalizedAcrName ==~ /[a-z0-9]{5,50}/)) {
                        error("ACR_NAME must resolve to 5-50 lowercase alphanumeric characters. Received: '${env.ACR_NAME_ENV}'")
                    }

                    def shortSha = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${shortSha}"
                    env.ACR_NAME_NORMALIZED = normalizedAcrName
                    env.FULL_IMAGE = "${env.ACR_LOGIN_SERVER_ENV}/${env.IMAGE_REPO_ENV}:${env.IMAGE_TAG}"
                }
                sh '''
                  az acr login --name "$ACR_NAME_NORMALIZED"
                  docker build -t "$FULL_IMAGE" .
                  docker push "$FULL_IMAGE"
                '''
            }
        }

        stage('Deploy Workload to AKS') {
            steps {
                sh '''
                  set -e
                  az aks get-credentials \
                    --resource-group "$AKS_RESOURCE_GROUP_ENV" \
                    --name "$AKS_CLUSTER_NAME_ENV" \
                    --overwrite-existing

                  sed "s|__NAMESPACE__|$K8S_NAMESPACE_ENV|g" k8s/namespace.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|$K8S_NAMESPACE_ENV|g; s|__IMAGE__|$FULL_IMAGE|g" k8s/deployment.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|$K8S_NAMESPACE_ENV|g" k8s/service.yaml | kubectl apply -f -

                  kubectl -n "$K8S_NAMESPACE_ENV" rollout status deployment/users-api --timeout=180s
                  kubectl -n "$K8S_NAMESPACE_ENV" get svc users-api -o wide
                '''
            }
        }

        stage('Install Ingress Stack') {
            when {
                expression { return params.INSTALL_INGRESS_STACK }
            }
            steps {
                sh '''
                  set -e
                  kubectl apply -f "$INGRESS_NGINX_MANIFEST_URL_ENV"
                  kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s

                  kubectl apply -f "$CERT_MANAGER_MANIFEST_URL_ENV"
                  kubectl -n cert-manager rollout status deployment/cert-manager --timeout=300s
                  kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=300s
                  kubectl -n cert-manager rollout status deployment/cert-manager-cainjector --timeout=300s
                '''
            }
        }

        stage('Deploy HTTPS Ingress') {
            when {
                expression { return params.ENABLE_INGRESS_TLS }
            }
            steps {
                sh '''
                  set -e
                  if [ -z "$INGRESS_HOST_ENV" ] || [ -z "$LETSENCRYPT_EMAIL_ENV" ]; then
                    echo "INGRESS_HOST and LETSENCRYPT_EMAIL are required when ENABLE_INGRESS_TLS=true"
                    exit 1
                  fi

                  sed "s|__CLUSTER_ISSUER_NAME__|$CLUSTER_ISSUER_NAME_ENV|g; s|__ACME_SERVER__|$ACME_SERVER_ENV|g; s|__ACME_EMAIL__|$LETSENCRYPT_EMAIL_ENV|g; s|__INGRESS_CLASS__|$INGRESS_CLASS_ENV|g" k8s/cluster-issuer.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|$K8S_NAMESPACE_ENV|g; s|__CLUSTER_ISSUER_NAME__|$CLUSTER_ISSUER_NAME_ENV|g; s|__INGRESS_CLASS__|$INGRESS_CLASS_ENV|g; s|__INGRESS_HOST__|$INGRESS_HOST_ENV|g; s|__TLS_SECRET_NAME__|$TLS_SECRET_NAME_ENV|g" k8s/ingress.yaml | kubectl apply -f -

                  kubectl -n "$K8S_NAMESPACE_ENV" get ingress users-api -o wide
                  kubectl -n "$K8S_NAMESPACE_ENV" get certificate || true
                  kubectl -n ingress-nginx get svc ingress-nginx-controller -o wide
                '''
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
    }
}
