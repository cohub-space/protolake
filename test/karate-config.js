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
    // Call a unary RPC. payload defaults to {} (no body); opts.headers is a
    // {name: value} map sent as request metadata, one -H per entry. Returns
    // the parsed JSON response; fails the scenario with grpcurl's output on
    // error or unparseable output.
    // The payload, target, method and headers reach bash as environment
    // variables, expanded inside double quotes, through karate.exec's args
    // form. Never the line form: Karate re-tokenizes a line and drops its
    // quotes before `sh -c` runs it, so a space would split a value and a
    // `*` would expand to file names, silently changing what gets sent.
    call: function (target, method, payload, opts) {
      // Karate copies the environment with Map.copyOf, which throws a bare
      // NullPointerException on an undefined value, so a missing target or
      // method (a service with no grpcTarget, say) fails here by name.
      if (target == null || method == null) {
        karate.fail('grpc.call(' + method + ') failed:\nmissing target or method (target: ' + target + ')');
      }
      opts = opts || {};
      var env = { GRPC_PAYLOAD: JSON.stringify(payload || {}),
                  GRPC_TARGET: target, GRPC_METHOD: method };
      // karate.toString renders a JS object and a def'd map alike as JSON.
      var headers = opts.headers ? JSON.parse(karate.toString(opts.headers)) : {};
      var count = 0;
      for (var name in headers) {
        env['GRPC_H' + count++] = name + ': ' + headers[name];
      }
      var script = 'args=(-plaintext)\n' +
        'for ((i = 0; i < ' + count + '; i++)); do v="GRPC_H$i"; args+=(-H "${!v}"); done\n' +
        'printf %s "$GRPC_PAYLOAD" | grpcurl "${args[@]}" -d @ "$GRPC_TARGET" "$GRPC_METHOD"';
      var raw = karate.exec({
        args: ['bash', '-c', script],
        env: env,
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
      return raw.split(',')
                .map(function (s) { return s.trim(); })
                .filter(function (s) { return s.length > 0; });
    }
  };

  return config;
}
