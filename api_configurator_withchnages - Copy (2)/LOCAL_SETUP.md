# Leah API Gateway & Postman Collection Generator

## Local Development Setup Guide

This guide explains how to import, install, configure, and run the API Gateway project on a local development machine. The project consists of a **Spring Boot Backend** and a **React + Vite Frontend**.

---

## 1. Prerequisites

Before starting, ensure the following software is installed on your local machine:

- **Java Development Kit (JDK) 17** or higher(https://www.youtube.com/watch?v=ykAhL1IoQUM)
- **Maven 3.8+** (for building the backend)(https://www.youtube.com/watch?v=cIneTHgkrQw)
- **Node.js v18+** and **npm** (for the frontend)(https://www.youtube.com/watch?v=lt5D2EWZMN0)

_(Note: No external database installation like MongoDB is required. The system runs on a lightweight, embedded H2 database!)_

---

## 2. Setting Up the Code

Extract the provided ZIP folder on your local machine and open it in your terminal or IDE:

```bash
# Example if using terminal:
cd path/to/extracted/the-api-store_er-main
```

The repository structure looks like this:

- `backend/` - The Spring Boot Java Application (API logic, H2 embedded DB, Generator Scrubber)
- `frontend/` - The React Vite Application (UI for selecting APIs and configuring the environment)

---

--You can follow this link to visualize the following process (https://youtu.be/eUR4Zt5AcZs)

## 3. Backend Setup (Spring Boot)

The backend runs on **port 8080** and uses an embedded **H2 Database** that requires zero installation!

1. **Navigate to the Backend Directory:**

   ```bash
   cd backend
   ```

2. **Verify Database Configuration:**
   The database runs entirely embedded. When you start the app, it automatically creates a single permanent file (`./data/compliance_db.mv.db`) in your backend directory to persist templates!

3. **Install Dependencies & Compile:**
   Run Maven to download all Java dependencies and compile the code:

   ```bash
   mvn clean install -DskipTests
   ```

4. **Start the Backend Server:**
   ```bash
   mvn spring-boot:run
   ```
   The backend API will start at `http://localhost:8080`.

---

## 4. Frontend Setup (React + Vite)

The frontend runs on **port 5173** and communicates directly with the backend.

1. **Navigate to the Frontend Directory:**
   Open a _new_ terminal window/tab and run:

   ```bash
   cd frontend
   ```

2. **Install Node Dependencies:**

   ```bash
   npm install
   ```

3. **Start the Frontend Development Server:**
   ```bash
   npm run dev
   ```
   The UI will be available at `http://localhost:5173`.

---

## 5. Synchronizing Swagger APIs (Database Hydration)

Since the H2 database is fresh (or whenever new APIs are released by the backend team), you must synchronize the Swagger templates into your local database.

The backend sync engine runs in **Upsert Mode**. This means you can sync multiple URLs (Legacy and Business) and they will append/update flawlessly without wiping each other out.

To sync, make sure the Backend is running, and send a `POST` request to the sync endpoint. You can use **Postman** or the **cURL** commands below:

**Import Legacy APIs:**

```bash
curl -X POST "http://localhost:8080/api/v1/generator/sync?url=https://cpai-productapi-stg.azurewebsites.net/swagger/v1/swagger.json"
```

**Import Maxis (Business) APIs:**

```bash
curl -X POST "http://localhost:8080/api/v1/generator/sync?url=https://maxis.api.clm.stg3.leahai.com/business/swagger/v1/swagger.json"
```

> **When to re-import in the future?** You only need to run these commands again if the backend team updates an API or releases a new one. Your local `compliance_db.mv.db` file will permanently store these APIs even if you restart your computer!

---

## 6. How to View Your Local Database

If you want to manually inspect the APIs saved in your database, you can use the built-in H2 Console directly from your browser!

1. Ensure the Spring Boot backend is running.
2. Open your web browser and go to: **[http://localhost:8080/h2-console](http://localhost:8080/h2-console)**
3. On the login screen, enter these exact credentials:
   - **JDBC URL:** `jdbc:h2:file:./data/compliance_db`
   - **User Name:** `sa`
   - **Password:** _(leave it completely empty)_
4. Click **Connect**. You can now run SQL queries against the `API_TEMPLATES` table!

---

## 7. Application Usage & Workflow

Once everything is set up and synced, follow this workflow to generate Postman Collections:

### Step 1: Open the UI

Navigate your browser to [http://localhost:5173](http://localhost:5173).

### Step 2: Environment Configuration

1. **Select API Target:** Use the dropdown at the top right to choose between the **Business API** (Maxis) or **Legacy API** (Execo).
2. **Fill Credentials:** Enter your email, password, and optionally a cloud instance/tenant name. These will be securely injected into the Postman Environment variables.

### Step 3: Select APIs to Generate

The UI dynamically fetches available API templates from your H2 database.

- **Legacy Mode:** Only legacy APIs are visible.
- **Business Mode:** Both Business and Legacy APIs are visible, enabling Hybrid downloads.

### Step 4: Workflows vs Loose APIs

- **Managed Workflows:** If you select an API that belongs to a predefined workflow (e.g., "Create Client"), the backend's _Scrubber Engine_ automatically gathers all exact dependency endpoints required for that flow (and rigidly strips everything else), guaranteeing a perfect execution chain in Postman.
- **Hybrid Support:** You can select a Business Workflow and a Legacy Workflow simultaneously. The generator will safely bundle both.

### Step 5: Generate & Download

Click **Download Collection**. The React app will send your selections to the Java backend. A `.json` file (`Leah_Collection_<tenant>.json`) is instantly downloaded.

### Step 6: Import into Postman

1. Open **Postman**.
2. Click **Import** and drop the `.json` file.
3. The Collection will appear with your variables and authentication pre-configured. You can run the entire folder to see the workflow in action!
