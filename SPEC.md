# structured-data-logger

## Functionality
- Collect and report on structured, tagged data as a journal entry.
- With each entry, always include:
  - timestamp
  - description
- Any other key:value pairs are optionally added to a journal entry.
  - When adding key:value pairs to an journal entry,
    present the user with an easy way to see recently- and commonly-used
    keys and values for those keys.
  - allow easy adding whole new keys
  - could be numeric values, free-form text, dropdown values
- persistent dashboards to show data based on sci code written by user
  - provide default starter scripts to show lists, counts, average, stddev,
    time between data items, windows of time
  - graphs
- sync a list of changes to the back-end
- examples of data to track:
  pills, mileage, bed time, eating (food and quantity), etc
- allow creation and edit of timestamped journal entries
- list them by date

## Project Repo Structure
/
- web - web front-end
- server - back-end server

## Tech Stack
- front-end:
  - rich, mobile web app with local storage
  - clojurescript
  - shadow-cljs
  - helix
  - sci code editor
  - functions offline as app in the mobile browser
- back-end:
  - clojure
  - deps.edn
  - podman kubernetes quadlet
  - database: xtdb
  - json/rest api for syncing
  - authentication for client user

## Example Projects for Inspiration

- ../event-logger
- ../event-logger-backend
- ../roadside/main
