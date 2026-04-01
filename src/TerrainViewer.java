//javac -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainViewer.java
//java -classpath ".;C:\Program Files\lwjgl-release-3.3.4-custom\*" TerrainViewer

import java.io.*;
import java.nio.*;
import java.util.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBImage; //ST
import org.lwjgl.system.MemoryStack; //ST

public class TerrainViewer {

    static float[] vx, vy, vz;
    static float[] nx, ny, nz;
    static int[] faceVI, faceNI;
    static int nVerts, nNorms, nFaceCorners;

    static float minY, maxY, cx, cz, size;

    static int textureId; //ST

    public static void main(String[] args) throws Exception {
        loadObj("fractal_terrain.obj");
        render();
    }

    static void loadObj(String path) throws IOException {
        List<Float> lVX = new ArrayList<>(), lVY = new ArrayList<>(), lVZ = new ArrayList<>();
        List<Float> lNX = new ArrayList<>(), lNY = new ArrayList<>(), lNZ = new ArrayList<>();
        List<Integer> lFVI = new ArrayList<>(), lFNI = new ArrayList<>();

        float minX=1e9f, maxX=-1e9f, minZ=1e9f, maxZ=-1e9f;
        minY=1e9f; maxY=-1e9f;

        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    float x=Float.parseFloat(p[1]), y=Float.parseFloat(p[2]), z=Float.parseFloat(p[3]);
                    lVX.add(x); lVY.add(y); lVZ.add(z);
                    if (x<minX) minX=x; if (x>maxX) maxX=x;
                    if (y<minY) minY=y; if (y>maxY) maxY=y;
                    if (z<minZ) minZ=z; if (z>maxZ) maxZ=z;
                } else if (line.startsWith("vn ")) {
                    String[] p = line.split("\\s+");
                    lNX.add(Float.parseFloat(p[1]));
                    lNY.add(Float.parseFloat(p[2]));
                    lNZ.add(Float.parseFloat(p[3]));
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    for (int i = 1; i <= 3; i++) {
                        String[] idx = p[i].split("//");
                        lFVI.add(Integer.parseInt(idx[0]) - 1);
                        lFNI.add(Integer.parseInt(idx[1]) - 1);
                    }
                }
            }
        }

        nVerts = lVX.size(); nNorms = lNX.size(); nFaceCorners = lFVI.size();
        vx = toArray(lVX); vy = toArray(lVY); vz = toArray(lVZ);
        nx = toArray(lNX); ny = toArray(lNY); nz = toArray(lNZ);
        faceVI = toIntArray(lFVI); faceNI = toIntArray(lFNI);

        cx = (minX + maxX) / 2f;
        cz = (minZ + maxZ) / 2f;
        size = Math.max(maxX - minX, maxZ - minZ);
    }

    static void render() {
        if (!GLFW.glfwInit()) throw new RuntimeException("GLFW init failed");

        long win = GLFW.glfwCreateWindow(900, 700, "Terrain Viewer", 0, 0);
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D); //ST

        textureId = loadTexture("terrain.png"); //ST

        while (!GLFW.glfwWindowShouldClose(win)) {
            int[] w = {0}, h = {0};
            GLFW.glfwGetFramebufferSize(win, w, h);
            GL11.glViewport(0, 0, w[0], h[0]);

            GL11.glClearColor(0.1f, 0.1f, 0.12f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            setupIsometric(w[0], h[0]);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId); //ST
            drawTerrain();

            GLFW.glfwSwapBuffers(win);
            GLFW.glfwPollEvents();
        }

        GLFW.glfwTerminate();
    }

    static void setupIsometric(int w, int h) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        float aspect = (float) w / h;
        float s = size * 0.35f; //ST (zoomed in more)
        GL11.glOrtho(-s*aspect, s*aspect, -s, s, -size*10, size*10);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glRotatef(35.264f, 1, 0, 0);
        GL11.glRotatef(-45f, 0, 1, 0);
        float midY = (minY + maxY) / 2f;
        GL11.glTranslatef(-cx, -midY, -cz);
    }

    static void drawTerrain() {
        GL11.glBegin(GL11.GL_TRIANGLES);
        for (int i = 0; i < nFaceCorners; i++) {
            int vi = faceVI[i];
            int ni = faceNI[i];

            GL11.glNormal3f(nx[ni], ny[ni], nz[ni]);

            float u = vx[vi] / size; //ST
            float v = vz[vi] / size; //ST
            GL11.glTexCoord2f(u, v); //ST

            GL11.glVertex3f(vx[vi], vy[vi], vz[vi]);
        }
        GL11.glEnd();
    }

    static int loadTexture(String path) { //ST
        ByteBuffer image;
        int width, height;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            image = STBImage.stbi_load(path, w, h, comp, 4);
            if (image == null) throw new RuntimeException("Failed to load PNG");

            width = w.get();
            height = h.get();
        }

        int texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);

        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, image);

        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        STBImage.stbi_image_free(image);
        return texId;
    }

    static float[] toArray(List<Float> l) {
        float[] a = new float[l.size()];
        for (int i=0;i<a.length;i++) a[i]=l.get(i);
        return a;
    }

    static int[] toIntArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i=0;i<a.length;i++) a[i]=l.get(i);
        return a;
    }
}