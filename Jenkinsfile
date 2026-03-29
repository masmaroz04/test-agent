pipeline {
    agent { label 'linux && docker' }

    options {
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(name: 'ACR_NAME', defaultValue: 'usersapiacr', description: 'Azure Container Registry name; full login server is also accepted')
        string(name: 'ACR_LOGIN_SERVER', defaultValue: 'usersapiacr.azurecr.io', description: 'ACR login server')
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
        // Node.js frontend parameters
        string(name: 'FRONTEND_IMAGE_REPO', defaultValue: 'node-frontend', description: 'Repository name in ACR for Node.js frontend')
        string(name: 'FRONTEND_BASE_URL', defaultValue: 'http://23.98.126.205', description: 'Public base URL of the frontend, e.g. http://<EXTERNAL-IP>')
        string(name: 'AUTH0_ISSUER_BASE_URL', defaultValue: 'https://dev-s4q7d35k5uyh6r3n.eu.auth0.com', description: 'Auth0 issuer base URL (no trailing slash)')
        string(name: 'AUTH0_AUDIENCE', defaultValue: 'https://users-api', description: 'Auth0 API audience identifier')
        string(name: 'AUTH0_CLIENT_ID', defaultValue: 'VJVLUuEmBQVE7AJmwwm2HkbJkEH0vBmC', description: 'Auth0 Regular Web App Client ID')
        password(name: 'POSTGRES_PASSWORD', defaultValue: 'usersapp', description: 'PostgreSQL password for usersapp user')
    }

    environment {
        AZURE_SP = credentials('azure-sp')
        AZURE_TENANT_ID = credentials('azure-tenant-id')
        AZURE_SUBSCRIPTION_ID = credentials('azure-subscription-id')
        AUTH0_CLIENT_SECRET = credentials('auth0-client-secret')
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
                        'ACR_LOGIN_SERVER': params.ACR_LOGIN_SERVER,
                        'AKS_RESOURCE_GROUP': params.AKS_RESOURCE_GROUP,
                        'AKS_CLUSTER_NAME': params.AKS_CLUSTER_NAME,
                        'K8S_NAMESPACE': params.K8S_NAMESPACE
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
                    env.IMAGE_TAG = "${env.BUILD_NUMBER}"
                    env.ACR_NAME_NORMALIZED = params.ACR_NAME.trim().toLowerCase()
                    env.FULL_IMAGE = "${params.ACR_LOGIN_SERVER}/${params.IMAGE_REPO}:${env.IMAGE_TAG}"
                    env.FRONTEND_FULL_IMAGE = "${params.ACR_LOGIN_SERVER}/${params.FRONTEND_IMAGE_REPO}:${env.IMAGE_TAG}"
                }
                sh '''
                  az acr login --name "$ACR_NAME_NORMALIZED"
                  docker buildx create --use --name jenkins-builder >/dev/null 2>&1 || docker buildx use jenkins-builder
                  docker buildx inspect --bootstrap
                  docker buildx build --platform linux/amd64 --provenance=false -t "$FULL_IMAGE" --push .
                  docker buildx build --platform linux/amd64 --provenance=false -t "$FRONTEND_FULL_IMAGE" --push node-frontend/
                '''
            }
        }

        stage('Deploy Workload to AKS') {
            steps {
                echo "Deploying to AKS resource group '${params.AKS_RESOURCE_GROUP}', cluster '${params.AKS_CLUSTER_NAME}', namespace '${params.K8S_NAMESPACE}'"
                sh """
                  set -e
                  az aks get-credentials \
                    --resource-group "${params.AKS_RESOURCE_GROUP}" \
                    --name "${params.AKS_CLUSTER_NAME}" \
                    --overwrite-existing

                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g" k8s/namespace.yaml | kubectl apply -f -

                  # Deploy PostgreSQL
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g; s|__POSTGRES_PASSWORD__|${params.POSTGRES_PASSWORD}|g" k8s/postgres-secret.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g" k8s/postgres-deployment.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g" k8s/postgres-service.yaml | kubectl apply -f -
                  kubectl -n "${params.K8S_NAMESPACE}" rollout status deployment/postgres --timeout=60s

                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g; s|__IMAGE__|$FULL_IMAGE|g" k8s/deployment.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g" k8s/service.yaml | kubectl apply -f -

                  kubectl -n "${params.K8S_NAMESPACE}" rollout status deployment/users-api --timeout=180s
                  kubectl -n "${params.K8S_NAMESPACE}" get svc users-api -o wide

                  # Deploy Node.js frontend
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g; s|__AUTH0_CLIENT_ID__|${params.AUTH0_CLIENT_ID}|g; s|__AUTH0_CLIENT_SECRET__|\$AUTH0_CLIENT_SECRET|g; s|__SESSION_SECRET__|\$(openssl rand -hex 32)|g" k8s/node-frontend-secret.yaml | kubectl apply -f -

                  FRONTEND_BASE="${params.FRONTEND_BASE_URL}"
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g; s|__FRONTEND_IMAGE__|$FRONTEND_FULL_IMAGE|g; s|__FRONTEND_BASE_URL__|\$FRONTEND_BASE|g; s|__AUTH0_ISSUER_BASE_URL__|${params.AUTH0_ISSUER_BASE_URL}|g; s|__AUTH0_AUDIENCE__|${params.AUTH0_AUDIENCE}|g" k8s/node-frontend-deployment.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g" k8s/node-frontend-service.yaml | kubectl apply -f -

                  kubectl -n "${params.K8S_NAMESPACE}" rollout status deployment/node-frontend --timeout=180s
                  kubectl -n "${params.K8S_NAMESPACE}" get svc node-frontend -o wide
                """
            }
        }

        stage('Install Ingress Stack') {
            when {
                expression { return params.INSTALL_INGRESS_STACK }
            }
            steps {
                sh """
                  set -e
                  kubectl apply -f "${params.INGRESS_NGINX_MANIFEST_URL}"
                  kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=300s

                  kubectl apply -f "${params.CERT_MANAGER_MANIFEST_URL}"
                  kubectl -n cert-manager rollout status deployment/cert-manager --timeout=300s
                  kubectl -n cert-manager rollout status deployment/cert-manager-webhook --timeout=300s
                  kubectl -n cert-manager rollout status deployment/cert-manager-cainjector --timeout=300s
                """
            }
        }

        stage('Deploy HTTPS Ingress') {
            when {
                expression { return params.ENABLE_INGRESS_TLS }
            }
            steps {
                sh """
                  set -e
                  if [ -z "${params.INGRESS_HOST}" ] || [ -z "${params.LETSENCRYPT_EMAIL}" ]; then
                    echo "INGRESS_HOST and LETSENCRYPT_EMAIL are required when ENABLE_INGRESS_TLS=true"
                    exit 1
                  fi

                  sed "s|__CLUSTER_ISSUER_NAME__|${params.CLUSTER_ISSUER_NAME}|g; s|__ACME_SERVER__|${params.ACME_SERVER}|g; s|__ACME_EMAIL__|${params.LETSENCRYPT_EMAIL}|g; s|__INGRESS_CLASS__|${params.INGRESS_CLASS}|g" k8s/cluster-issuer.yaml | kubectl apply -f -
                  sed "s|__NAMESPACE__|${params.K8S_NAMESPACE}|g; s|__CLUSTER_ISSUER_NAME__|${params.CLUSTER_ISSUER_NAME}|g; s|__INGRESS_CLASS__|${params.INGRESS_CLASS}|g; s|__INGRESS_HOST__|${params.INGRESS_HOST}|g; s|__TLS_SECRET_NAME__|${params.TLS_SECRET_NAME}|g" k8s/ingress.yaml | kubectl apply -f -

                  kubectl -n "${params.K8S_NAMESPACE}" get ingress users-api -o wide
                  kubectl -n "${params.K8S_NAMESPACE}" get certificate || true
                  kubectl -n ingress-nginx get svc ingress-nginx-controller -o wide
                """
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
    }
}
