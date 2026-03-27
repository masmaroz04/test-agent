require('dotenv').config();
const express = require('express');
const { auth, requiresAuth } = require('express-openid-connect');
const axios = require('axios');

const app = express();
const port = process.env.PORT || 3000;

const config = {
  authRequired: false,
  auth0Logout: true,
  secret: process.env.SESSION_SECRET,
  baseURL: process.env.BASE_URL,
  clientID: process.env.AUTH0_CLIENT_ID,
  clientSecret: process.env.AUTH0_CLIENT_SECRET,
  issuerBaseURL: process.env.AUTH0_ISSUER_BASE_URL,
  authorizationParams: {
    audience: process.env.AUTH0_AUDIENCE,
    response_type: 'code',
    scope: 'openid profile email',
  },
};

app.use(auth(config));

app.get('/', (req, res) => {
  if (req.oidc.isAuthenticated()) {
    res.redirect('/users');
  } else {
    res.send(`
      <html><body style="font-family:sans-serif;max-width:600px;margin:80px auto;text-align:center">
        <h1>Users API</h1>
        <p>Please log in to view users.</p>
        <a href="/login" style="padding:10px 24px;background:#635dff;color:white;text-decoration:none;border-radius:6px">Log In</a>
      </body></html>
    `);
  }
});

app.get('/users', requiresAuth(), async (req, res) => {
  try {
    const token = req.oidc.accessToken?.access_token;
    const apiUrl = process.env.USERS_API_URL || 'http://users-api:8080';
    const response = await axios.get(`${apiUrl}/api/v1/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    const users = response.data;
    const rows = users.map(u =>
      `<tr><td>${u.id}</td><td>${u.fullName}</td><td>${u.email}</td></tr>`
    ).join('');

    res.send(`
      <html><body style="font-family:sans-serif;max-width:800px;margin:40px auto">
        <h1>Users</h1>
        <p>Hello, ${req.oidc.user.name} &nbsp;|&nbsp; <a href="/logout">Log Out</a></p>
        <table border="1" cellpadding="8" cellspacing="0" style="width:100%;border-collapse:collapse">
          <thead style="background:#f0f0f0">
            <tr><th>ID</th><th>Full Name</th><th>Email</th></tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </body></html>
    `);
  } catch (err) {
    const status = err.response?.status;
    if (status === 403) {
      return res.status(403).send(`
        <html><body style="font-family:sans-serif;max-width:600px;margin:80px auto;text-align:center">
          <h2>Access Denied</h2>
          <p>Your account (<strong>${req.oidc.user.email}</strong>) is not authorized to access this application.</p>
          <a href="/logout" style="padding:10px 24px;background:#e53e3e;color:white;text-decoration:none;border-radius:6px">Log Out</a>
        </body></html>
      `);
    }
    const msg = status === 401 ? 'Unauthorized — token issue' : `Error calling API: ${err.message}`;
    res.status(500).send(`<p style="color:red">${msg}</p><a href="/">Back</a>`);
  }
});

app.get('/token', requiresAuth(), (req, res) => {
  res.send(`<pre>${req.oidc.accessToken?.access_token}</pre>`);
});

app.listen(port, () => console.log(`Frontend running on port ${port}`));
