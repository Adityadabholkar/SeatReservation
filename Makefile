URL ?= http://localhost:8080

.PHONY: up down logs burst reset

up:        ## build and start app + postgres
	docker compose up --build -d

down:
	docker compose down

reset:     ## stop and wipe the database
	docker compose down -v

logs:
	docker compose logs -f app

burst:     ## make burst            (local)   |   make burst URL=https://your-app.onrender.com
	./burst.sh $(URL)
