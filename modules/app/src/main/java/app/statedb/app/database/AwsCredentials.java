package app.statedb.app.database;

/** AWS credentials used at the ECS task-role boundary. */
record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {

  static AwsCredentials basic(String accessKeyId, String secretAccessKey) {
    return new AwsCredentials(accessKeyId, secretAccessKey, null);
  }
}
