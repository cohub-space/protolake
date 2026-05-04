// Karate environment config for protolake.
// Generated scaffold-once by CoHub. Edit to add new envs or override URLs.
function fn() {
  var env = karate.env || 'local';
  karate.log('karate env:', env);
  var config = {
    env: env,
    services: {
      proto_lake: {
        host: 'localhost',
        httpPort: 8085,
        httpUrl: 'http://localhost:8085',
        grpcPort: 9050,
        grpcTarget: 'localhost:9050'
      }
    }
  };
  return config;
}
