{{- define "statedb.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "statedb.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "statedb.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "statedb.labels" -}}
app.kubernetes.io/name: {{ include "statedb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "statedb.selectorLabels" -}}
app.kubernetes.io/name: {{ include "statedb.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "statedb.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "statedb.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Secret holding the session encryption key: either the user's or ours. */}}
{{- define "statedb.sessionSecretName" -}}
{{- if .Values.sessionExistingSecret -}}
{{- .Values.sessionExistingSecret -}}
{{- else -}}
{{- printf "%s-session" (include "statedb.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "statedb.sessionSecretKey" -}}
{{- if .Values.sessionExistingSecret -}}
{{- .Values.sessionExistingSecretKey -}}
{{- else -}}
session-encryption-secret
{{- end -}}
{{- end -}}
