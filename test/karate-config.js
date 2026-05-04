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

  // gRPC helper — wraps grpcurl shell-out so feature files can do:
  //   * def resp = grpc.call(services.foo.grpcTarget, 'pkg.Svc/Method', {body: 'x'})
  //   * match resp.field == 'expected'
  // instead of building command strings and string-matching stdout. Uses
  // karate.exec args[] form so JSON payloads don't need shell escaping.
  // Unary RPCs only — streaming responses won't parse as a single JSON
  // object (drop to a raw karate.exec call for those).
  config.grpc = {
    call: function (target, method, payload, opts) {
      opts = opts || {};
      var json = JSON.stringify(payload || {});
      var headers = '';
      if (opts.headers) {
        for (var h in opts.headers) {
          headers += ' -H "' + h + ': ' + opts.headers[h] + '"';
        }
      }
      var cmd = 'printf %s "$GRPC_PAYLOAD" | grpcurl -plaintext' + headers +
                ' -d @ ' + target + ' ' + method;
      var raw = karate.exec({
        line: cmd,
        useShell: true,
        env: { GRPC_PAYLOAD: json },
        redirectErrorStream: true
      });
      try { return JSON.parse(raw); }
      catch (e) { karate.fail('grpc.call(' + method + ') failed:\n' + raw); }
    },
    list: function (target) {
      var raw = karate.exec({
        line: 'grpcurl -plaintext ' + target + ' list | paste -sd, -',
        useShell: true,
        redirectErrorStream: true
      });
      return raw.split(',').filter(function (s) { return s.length > 0; });
    }
  };

  return config;
}
