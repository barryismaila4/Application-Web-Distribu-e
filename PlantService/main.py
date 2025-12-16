from fastapi import FastAPI, Depends, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from sqlalchemy import create_engine, Column, Integer, String, Text
from sqlalchemy.orm import sessionmaker, declarative_base, Session
import requests
import threading

# =================================================
# DATABASE CONFIGURATION (MariaDB)
# =================================================
DB_CONFIG = {
    "host": "localhost",
    "port": 3307,
    "user": "root",
    "password": "rootroot",
    "database": "plandb"
}

DATABASE_URL = (
    f"mysql+pymysql://{DB_CONFIG['user']}:"
    f"{DB_CONFIG['password']}@"
    f"{DB_CONFIG['host']}:{DB_CONFIG['port']}/"
    f"{DB_CONFIG['database']}"
)

# =================================================
# EUREKA CONFIGURATION
# =================================================
EUREKA_SERVER = "http://localhost:8761/eureka"
SERVICE_NAME = "PLANT-SERVICE"
SERVICE_PORT = 8000
SERVICE_HOST = "127.0.0.1"  # Force localhost pour éviter 503

# =================================================
# USER SERVICE (via API Gateway)
# =================================================
USER_SERVICE_URL = "http://localhost:8090/api/users/exists"

# =================================================
# FASTAPI APP
# =================================================
app = FastAPI(title="PLANT-SERVICE")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:4200", "http://localhost:8085"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# =================================================
# DATABASE
# =================================================
engine = create_engine(DATABASE_URL, echo=True)
SessionLocal = sessionmaker(bind=engine)
Base = declarative_base()

# =================================================
# ENTITY
# =================================================
class Plant(Base):
    __tablename__ = "plants"

    id = Column(Integer, primary_key=True, index=True)
    name = Column(String(100), nullable=False)
    description = Column(Text)
    user_id = Column(Integer, nullable=False)

Base.metadata.create_all(bind=engine)

# =================================================
# SCHEMAS
# =================================================
class PlantCreate(BaseModel):
    name: str
    description: str
    user_id: int

class PlantResponse(PlantCreate):
    id: int
    class Config:
        orm_mode = True

# =================================================
# DB DEPENDENCY
# =================================================
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

# =================================================
# UTILS
# =================================================
def check_user_exists(user_id: int) -> bool:
    try:
        r = requests.get(f"{USER_SERVICE_URL}/{user_id}")
        return r.status_code == 200 and r.json() is True
    except:
        return False

# =================================================
# CRUD ENDPOINTS
# =================================================
@app.post("/api/plants", response_model=PlantResponse)
def create_plant(plant: PlantCreate, db: Session = Depends(get_db)):
    if not check_user_exists(plant.user_id):
        raise HTTPException(status_code=400, detail="User does not exist")

    new_plant = Plant(**plant.dict())
    db.add(new_plant)
    db.commit()
    db.refresh(new_plant)
    return new_plant


@app.get("/api/plants", response_model=list[PlantResponse])
def get_all_plants(db: Session = Depends(get_db)):
    return db.query(Plant).all()


@app.get("/api/plants/{plant_id}", response_model=PlantResponse)
def get_plant(plant_id: int, db: Session = Depends(get_db)):
    plant = db.query(Plant).filter(Plant.id == plant_id).first()
    if not plant:
        raise HTTPException(status_code=404, detail="Plant not found")
    return plant


@app.put("/api/plants/{plant_id}", response_model=PlantResponse)
def update_plant(plant_id: int, plant: PlantCreate, db: Session = Depends(get_db)):
    db_plant = db.query(Plant).filter(Plant.id == plant_id).first()
    if not db_plant:
        raise HTTPException(status_code=404, detail="Plant not found")

    if not check_user_exists(plant.user_id):
        raise HTTPException(status_code=400, detail="User does not exist")

    db_plant.name = plant.name
    db_plant.description = plant.description
    db_plant.user_id = plant.user_id
    db.commit()
    db.refresh(db_plant)
    return db_plant


@app.delete("/api/plants/{plant_id}")
def delete_plant(plant_id: int, db: Session = Depends(get_db)):
    plant = db.query(Plant).filter(Plant.id == plant_id).first()
    if not plant:
        raise HTTPException(status_code=404, detail="Plant not found")

    db.delete(plant)
    db.commit()
    return {"message": "Plant deleted"}

# =================================================
# EUREKA REGISTRATION
# =================================================
def register_with_eureka():
    url = f"{EUREKA_SERVER}/apps/{SERVICE_NAME}"

    payload = {
        "instance": {
            "instanceId": f"{SERVICE_NAME}:127.0.0.1:{SERVICE_PORT}",
            "hostName": "localhost",
            "app": SERVICE_NAME,
            "ipAddr": "127.0.0.1",
            "status": "UP",
            "port": {"$": SERVICE_PORT, "@enabled": "true"},
            "homePageUrl": f"http://127.0.0.1:{SERVICE_PORT}",
            "statusPageUrl": f"http://127.0.0.1:{SERVICE_PORT}/docs",
            "healthCheckUrl": f"http://127.0.0.1:{SERVICE_PORT}/docs",
            "dataCenterInfo": {
                "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                "name": "MyOwn"
            }
        }
    }

    headers = {"Content-Type": "application/json"}
    requests.post(url, json=payload, headers=headers)
    print("✅ PLANT-SERVICE registered in Eureka (localhost)")

@app.on_event("startup")
def startup_event():
    threading.Thread(target=register_with_eureka).start()
