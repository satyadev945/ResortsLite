import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080/api';

// Configure axios to send credentials (cookies) with requests
axios.defaults.withCredentials = true;

const authService = {
  login: async (username, password) => {
    try {
      const response = await axios.post(`${API_BASE_URL}/auth/login`, {
        username,
        password
      });
      return response.data;
    } catch (error) {
      throw error.response?.data || { success: false, message: 'Login failed' };
    }
  },

  logout: async () => {
    try {
      const response = await axios.post(`${API_BASE_URL}/auth/logout`);
      return response.data;
    } catch (error) {
      throw error.response?.data || { success: false, message: 'Logout failed' };
    }
  },

  getCurrentUser: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/auth/me`);
      return response.data;
    } catch (error) {
      throw error.response?.data || { authenticated: false };
    }
  },

  isAuthenticated: async () => {
    try {
      const user = await authService.getCurrentUser();
      return user.authenticated;
    } catch (error) {
      return false;
    }
  }
};

// Setup axios interceptor to handle 401 responses
axios.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      // Redirect to login on 401
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export default authService;
