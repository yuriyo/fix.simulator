import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';
import './index.css';

// Every /api request carries the admin bearer token. The token is asked for once per browser
// session (kept in sessionStorage) and cleared on 401 so the next request prompts again.
const TOKEN_KEY = 'adminToken';
const nativeFetch = window.fetch.bind(window);

function currentToken() {
  try {
    let token = sessionStorage.getItem(TOKEN_KEY);
    if (!token) {
      token = window.prompt('Admin token (see ADMIN_TOKEN / admin.token on the server):') || '';
      if (token) sessionStorage.setItem(TOKEN_KEY, token);
    }
    return token;
  } catch {
    return '';
  }
}

window.fetch = async (input, init = {}) => {
  const url = typeof input === 'string' ? input : input.url;
  if (!url.startsWith('/api')) return nativeFetch(input, init);
  const headers = new Headers(init.headers || {});
  const token = currentToken();
  if (token) headers.set('Authorization', `Bearer ${token}`);
  const res = await nativeFetch(input, { ...init, headers });
  if (res.status === 401) {
    try { sessionStorage.removeItem(TOKEN_KEY); } catch { /* storage unavailable */ }
  }
  return res;
};

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
