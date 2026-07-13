Render deployment instructions for this Spring Boot app

1) Build Docker image locally (from repo root):

```bash
docker build -t aianalysis:latest .
```

2) Test locally:

```bash
docker run -p 8080:8080 aianalysis:latest
# then open http://localhost:8080
```

3) Push to a registry (Docker Hub example):

```bash
docker tag aianalysis:latest <your-dockerhub-username>/aianalysis:latest
docker push <your-dockerhub-username>/aianalysis:latest
```

4) On Render.com: Create a new Web Service
- Select Docker
- For the image, point to your Container Registry (Docker Hub, GitHub Container Registry, etc.) and the pushed image tag
- Set the Start Command: `java -jar /app/app.jar`
- Set the Port to `8080`
- Add environment variables from `src/main/resources/application.properties` as needed (e.g., database connection, mail settings)

5) (Optional) If using Render's Dockerfile build from repo, set the Build Command to `./mvnw -B -DskipTests package` and Start Command `java -jar target/*.jar`.
