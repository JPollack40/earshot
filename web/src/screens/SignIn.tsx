import { useState } from "react";
import { supabase } from "../supabase";

export function SignIn() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [show, setShow] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password });
    setBusy(false);
    if (error) setError(error.message);
  };

  return (
    <form className="signin" onSubmit={submit}>
      <h1>Earshot</h1>
      <p className="muted">Sign in with your Earshot account. You'll stay signed in on this browser.</p>
      <input type="email" placeholder="Email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} />
      <div className="password">
        <input
          type={show ? "text" : "password"}
          placeholder="Password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <button type="button" className="text" onClick={() => setShow(!show)}>
          {show ? "Hide" : "Show"}
        </button>
      </div>
      {error && <div className="error">{error}</div>}
      <button className="primary" disabled={busy || !email || !password}>
        {busy ? "Signing in…" : "Sign in"}
      </button>
      <p className="muted small">
        On Android? <a href="https://github.com/JPollack40/earshot/releases/latest" target="_blank" rel="noreferrer">Get the Earshot app</a> (open the .apk on your phone).
      </p>
    </form>
  );
}
